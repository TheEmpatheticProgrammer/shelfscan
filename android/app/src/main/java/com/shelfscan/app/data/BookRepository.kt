package com.shelfscan.app.data

import android.content.Context
import android.net.Uri
import com.shelfscan.app.data.remote.BookDto
import com.shelfscan.app.data.remote.BookRefDto
import com.shelfscan.app.data.remote.CoversRequest
import com.shelfscan.app.data.remote.DetailsRequest
import com.shelfscan.app.data.remote.IsbnRequest
import com.shelfscan.app.data.remote.OwnedBookDto
import com.shelfscan.app.data.remote.RecommendRequest
import com.shelfscan.app.data.remote.RecommendationDto
import com.shelfscan.app.data.remote.SearchRequest
import com.shelfscan.app.data.remote.SeriesGapDto
import com.shelfscan.app.data.remote.SeriesRequest
import com.shelfscan.app.data.remote.ShelfApi
import com.shelfscan.app.data.remote.UpcomingRequest
import com.shelfscan.app.util.ImageUtils
import com.shelfscan.app.util.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

class BookRepository(
    private val context: Context,
    private val dao: BookDao,
    private val wishlistDao: WishlistDao,
    private val api: ShelfApi,
    private val offline: OfflineStore,
    private val pendingStore: PendingScanStore,
) {
    /** Scans saved while the server was unreachable. */
    val pendingScans: StateFlow<List<PendingScan>> = pendingStore.scans

    val books: Flow<List<Book>> = dao.observeAll()

    /** Want to read: books you don't own yet, saved from suggestions. */
    val wishlist: Flow<List<WishlistItem>> = wishlistDao.observeAll()
    val wishlistKeys: Flow<Set<String>> = wishlist.map { items -> items.map { it.matchKey }.toSet() }

    private val prefs = context.getSharedPreferences("shelfscan", Context.MODE_PRIVATE)
    private val coverUpgrade = Mutex()

    fun book(id: Long): Flow<Book?> = dao.observe(id)

    suspend fun ownedKeys(): Set<String> = dao.allKeys().toSet()

    /** Downscaled JPEGs ready to upload (also what the offline queue keeps). */
    suspend fun preparePhotos(photos: List<Uri>): List<ByteArray> = photos.map { ImageUtils.prepareForUpload(context, it) }

    suspend fun identify(photos: List<ByteArray>): Pair<List<BookDto>, String?> {
        val parts = photos.mapIndexed { i, bytes ->
            MultipartBody.Part.createFormData("images", "photo_$i.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
        }
        val res = api.identify(parts)
        return res.books to res.notes
    }

    // --- Offline scan queue ---

    /** Keep photos that couldn't be sent; the queue worker reads them once the server is reachable. */
    suspend fun queueScan(photos: List<ByteArray>) {
        pendingStore.enqueue(photos)
        ScanQueueWorker.schedule(context)
    }

    suspend fun refreshPending() = pendingStore.refresh()

    suspend fun discardPending(id: String) = pendingStore.remove(id)

    /**
     * Sends every queued scan. Returns false if any still couldn't reach the server (try again later).
     * A scan the server rejects outright (4xx) is marked failed rather than retried forever.
     */
    suspend fun processPendingScans(): Boolean {
        pendingStore.refresh()
        var allSent = true
        for (scan in pendingStore.scans.value.filter { it.state == PendingScan.QUEUED }) {
            try {
                val (books, notes) = identify(pendingStore.photos(scan.id))
                pendingStore.update(scan.id) { it.copy(state = PendingScan.READY, books = books, notes = notes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                val permanent = e.code() in 400..499
                if (!permanent) allSent = false
                pendingStore.update(scan.id) {
                    it.copy(
                        state = if (permanent) PendingScan.FAILED else PendingScan.QUEUED,
                        attempts = it.attempts + 1, lastError = e.friendlyMessage(),
                    )
                }
            } catch (e: Exception) { // can't reach the server, timed out, ...
                allSent = false
                pendingStore.update(scan.id) { it.copy(attempts = it.attempts + 1, lastError = e.friendlyMessage()) }
            }
        }
        return allSent
    }

    /** Returns how many were actually added (duplicates are skipped). A book you now own leaves Want to read. */
    suspend fun add(books: List<BookDto>): Int {
        val added = dao.insertAll(books.map { it.toEntity() }).count { it != -1L }
        wishlistDao.deleteByKeys(books.map { it.matchKey })
        if (added > 0) prefetchDetails() // get their pages ready before anyone opens them
        return added
    }

    suspend fun setStatus(book: Book, status: String) = dao.setStatus(book.id, status)

    suspend fun setStatus(ids: Collection<Long>, status: String) = dao.setStatusMany(ids.toList(), status)

    /** Removes the books and returns them, so the removal can be undone with [restore]. */
    suspend fun delete(ids: Collection<Long>): List<Book> {
        val books = dao.byIds(ids.toList())
        dao.deleteMany(ids.toList())
        return books
    }

    suspend fun restore(books: List<Book>) { dao.insertAll(books) }

    // --- Want to read ---

    /** Save a suggestion to Want to read, or remove it if it's already there. */
    suspend fun toggleWantToRead(book: BookDto, reason: String?) {
        val key = book.matchKey
        if (wishlistDao.all().any { it.matchKey == key }) {
            wishlistDao.deleteByKeys(listOf(key))
        } else {
            wishlistDao.insertAll(listOf(book.toWishlistItem(reason)))
        }
    }

    /** Save several books to Want to read at once, skipping any already there. Returns how many were added. */
    suspend fun addToWantToRead(items: List<Pair<BookDto, String?>>): Int {
        val saved = wishlistDao.all().map { it.matchKey }.toSet()
        val fresh = items.distinctBy { it.first.matchKey }.filter { it.first.matchKey !in saved }
        if (fresh.isNotEmpty()) wishlistDao.insertAll(fresh.map { (book, reason) -> book.toWishlistItem(reason) })
        return fresh.size
    }

    /** "I have this book": it moves from Want to read into the library. */
    suspend fun moveToLibrary(item: WishlistItem) {
        dao.insert(item.toBook())
        wishlistDao.deleteByKeys(listOf(item.matchKey))
        prefetchDetails()
    }

    // --- Covers ---

    suspend fun coverCandidates(book: Book): List<String> =
        api.coverCandidates(BookRefDto(book.title, book.author, book.isbn)).candidates

    /** A cover the user picked on the book page. Remembered, so automatic cover updates leave it alone. */
    suspend fun setCover(book: Book, url: String) {
        dao.setCover(book.id, url, System.currentTimeMillis())
        val picked = pickedCovers() + book.id.toString()
        prefs.edit().putStringSet(PICKED_COVERS, picked).apply()
    }

    private fun pickedCovers(): Set<String> = prefs.getStringSet(PICKED_COVERS, emptySet()).orEmpty().toSet()

    /**
     * Looks again for covers of books that still have none, at most every few days per book, so a
     * temporary outage or a later catalog addition fixes them without anyone noticing.
     */
    suspend fun retryMissingCovers() = coverUpgrade.withLock {
        val now = System.currentTimeMillis()
        dao.missingCovers(before = now - COVER_RETRY_MS).chunked(25).forEach { batch ->
            val found = api.covers(CoversRequest(batch.map { BookRefDto(it.title, it.author, it.isbn) })).covers
            val unchanged = mutableListOf<Long>()
            batch.zip(found).forEach { (book, cover) ->
                val url = cover.coverUrl
                if (url != null && url != book.coverUrl) dao.setCover(book.id, url, now) else unchanged += book.id
            }
            dao.markCoverChecked(unchanged, now)
        }
    }

    // --- Search ---

    /** Books matching a typed title or author. */
    suspend fun search(query: String): List<BookDto> = api.search(SearchRequest(query)).books

    // --- Barcode ---

    /** The book behind an ISBN barcode, or null if no catalog knows it. */
    suspend fun lookupIsbn(isbn: String): BookDto? = api.lookupIsbn(IsbnRequest(isbn)).book

    // --- Backup ---

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val books = dao.all()
        val export = LibraryExport(books = books.map { it.toExported() }, wantToRead = wishlistDao.all().map { it.toExported() })
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open that file for writing")
        out.use { it.write(backupJson.encodeToString(LibraryExport.serializer(), export).toByteArray()) }
        books.size
    }

    /** Adds what the file has and the library doesn't; nothing already here is changed. */
    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Couldn't open that file")
        val export = runCatching { backupJson.decodeFromString(LibraryExport.serializer(), text) }.getOrNull()
        if (export == null || export.format != LibraryExport.FORMAT) error("That isn't a Shelfsight library file")
        val books = export.books.filter { it.title.isNotBlank() && it.author.isNotBlank() }.map { it.toBook() }
        val addedBooks = dao.insertAll(books).count { it != -1L }
        val owned = dao.allKeys().toSet()
        val wishes = export.wantToRead.filter { it.title.isNotBlank() }.map { it.toWishlistItem() }.filter { it.matchKey !in owned }
        val addedWishes = wishlistDao.insertAll(wishes).count { it != -1L }
        if (addedBooks > 0) prefetchDetails()
        ImportResult(addedBooks, addedWishes, skipped = export.books.size + export.wantToRead.size - addedBooks - addedWishes)
    }

    /** Saving a different title or author makes it a different book: drop the old synopsis and cover. */
    suspend fun update(book: Book) {
        val key = matchKeyOf(book.title, book.author)
        val renamed = key != book.matchKey
        dao.update(
            if (renamed) book.copy(matchKey = key, synopsis = null, coverUrl = null, isbn = null, description = null)
            else book
        )
    }

    /**
     * Fills in the book page: a large cover, the description and a short synopsis. Asks the server only
     * while the synopsis is missing, then keeps the answer, so a book page is instant from the second visit.
     */
    suspend fun loadDetails(book: Book) = lockFor("details:${book.id}").withLock {
        // Re-read under the lock: the background prefetch may have just filled it in.
        val current = dao.byId(book.id) ?: return@withLock
        if (current.synopsis != null) return@withLock
        val d = api.bookDetails(DetailsRequest(current.title, current.author, current.isbn, current.description))
        dao.update(
            current.copy(
                coverUrl = if (current.id.toString() in pickedCovers()) current.coverUrl else d.coverUrl ?: current.coverUrl,
                isbn = current.isbn ?: d.isbn,
                description = current.description ?: d.description,
                // An empty synopsis marks "looked, nothing to summarize" so it isn't asked for again.
                synopsis = d.synopsis ?: "",
                publishedDate = current.publishedDate ?: d.publishedDate,
                pageCount = current.pageCount ?: d.pageCount,
            )
        )
    }

    // --- Background prefetch of book pages ---

    /** Ask the background job to get book pages ready (synopsis, large cover, similar books). */
    fun prefetchDetails(afterAdding: Boolean = true) = DetailsPrefetchWorker.schedule(context, afterAdding)

    /**
     * One pass of the background prefetch. Returns false if the server couldn't be reached, so the job
     * tries again later; a book the server can't do anything with is skipped rather than retried forever.
     */
    suspend fun prefetchPendingDetails(): Boolean {
        // 1. Synopsis and cover for every book never looked up, newest first. Cheap for books with short
        //    descriptions (no model call), so this also quietly backfills the existing library.
        while (true) {
            val next = dao.missingDetails(limit = 1).firstOrNull() ?: break
            when (attempt { loadDetails(next) }) {
                Attempt.Ok -> Unit
                Attempt.Unreachable -> return false
                Attempt.Unusable -> dao.update(next.copy(synopsis = "")) // nothing to fetch: don't ask again
            }
        }
        // 2. Similar books for recently added books. Each is a model call, so only recent ones.
        for (book in dao.addedSince(System.currentTimeMillis() - SIMILAR_PREFETCH_WINDOW_MS)) {
            if (cachedSimilar(book)?.let { isFresh(it.savedAt) } == true) continue
            if (attempt { similarTo(book, refresh = true) } == Attempt.Unreachable) return false
        }
        return true
    }

    private enum class Attempt { Ok, Unreachable, Unusable }

    /** Unreachable = worth retrying later (no connection, timeout, server error); Unusable = won't get better. */
    private suspend fun attempt(block: suspend () -> Unit): Attempt = try {
        block()
        Attempt.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Attempt.Unreachable
    } catch (e: HttpException) {
        if (e.code() >= 500 || e.code() == 401) Attempt.Unreachable else Attempt.Unusable
    } catch (e: Exception) {
        Attempt.Unusable
    }

    /** "Similar books" for a book page, remembered for this session. */
    /** The similar books kept on the phone for this book, however old (null if never fetched). */
    suspend fun cachedSimilar(book: Book): Saved<List<RecommendationDto>>? = offline.load(similarKey(book), RECS)

    fun isFresh(savedAt: Long) = System.currentTimeMillis() - savedAt < SIMILAR_TTL_MS

    /**
     * "Similar books" for a book page. Served from the phone when there's a copy less than a week old
     * (usually because the background prefetch got it); otherwise asked of the server and kept.
     */
    suspend fun similarTo(book: Book, refresh: Boolean = false): Fetched<List<RecommendationDto>> =
        lockFor(similarKey(book)).withLock {
            if (!refresh) cachedSimilar(book)?.takeIf { isFresh(it.savedAt) }?.let { return@withLock Fetched(it.data) }
            val shelf = dao.all().sortedByDescending { it.author == book.author }.take(150)
            withOfflineCopy(similarKey(book), RECS) {
                api.recommendations(
                    RecommendRequest(
                        books = shelf.map { OwnedBookDto(it.title, it.author, it.genres) } + wantToReadForServer(),
                        limit = 8,
                        similarTo = OwnedBookDto(book.title, book.author, book.genres),
                    )
                ).recommendations
            }
        }

    private fun similarKey(book: Book) = "similar:${book.matchKey}"

    /** One lock per book and job, so the page and the background prefetch never fetch the same thing twice. */
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(key: String): Mutex = locks.getOrPut(key) { Mutex() }

    /**
     * One-time pass that asks the server again for every book's cover. v2 swapped small covers for large
     * ones; v3 replaces covers of the wrong edition (another language, or a guide or adaptation of the book).
     * Covers the user picked are kept. If the server can't be reached it simply runs again on the next launch.
     */
    suspend fun upgradeCoversOnce() = coverUpgrade.withLock {
        if (prefs.getBoolean(COVERS_V3, false)) return@withLock
        val picked = pickedCovers()
        dao.all().filterNot { it.id.toString() in picked }.chunked(25).forEach { batch ->
            val found = api.covers(CoversRequest(batch.map { BookRefDto(it.title, it.author, it.isbn) })).covers
            batch.zip(found).forEach { (book, cover) ->
                val url = cover.coverUrl
                if (url != null && url != book.coverUrl) dao.update(book.copy(coverUrl = url, isbn = book.isbn ?: cover.isbn))
            }
        }
        prefs.edit().putBoolean(COVERS_V3, true).apply()
    }

    suspend fun delete(book: Book) = dao.delete(book)

    /** Pass an author or a genre to focus the suggestions; neither means "everything I own". */
    suspend fun recommendations(author: String? = null, genre: String? = null, limit: Int = 10): Fetched<List<RecommendationDto>> {
        val owned = dao.all()
        if (owned.isEmpty()) return Fetched(emptyList())
        // Send the books the focus is about first, then the most recent: 150 is plenty of taste signal.
        val relevant = { b: Book ->
            (author != null && b.authors.any { it.equals(author, ignoreCase = true) }) ||
                (genre != null && b.genres.any { it.equals(genre, ignoreCase = true) })
        }
        val shelf = owned.sortedByDescending(relevant).take(150)
        val body = RecommendRequest(
            books = shelf.map { OwnedBookDto(it.title, it.author, it.genres) } + wantToReadForServer(),
            limit = limit,
            author = author,
            genre = genre,
        )
        return withOfflineCopy("recs:${author.orEmpty()}|${genre.orEmpty()}", RECS) { api.recommendations(body).recommendations }
    }

    /** Books already on Want to read go along too, so they aren't suggested again (and they reflect taste). */
    private suspend fun wantToReadForServer() = wishlistDao.all().take(50).map { OwnedBookDto(it.title, it.author, it.genres) }

    private companion object {
        const val COVERS_V3 = "covers_v3_done"
        const val PICKED_COVERS = "picked_cover_ids"
        const val COVER_RETRY_MS = 3L * 24 * 60 * 60 * 1000 // look again after three days
        const val SIMILAR_TTL_MS = 7L * 24 * 60 * 60 * 1000 // similar books are reused for a week
        const val SIMILAR_PREFETCH_WINDOW_MS = 14L * 24 * 60 * 60 * 1000 // prefetch them for books added in 2 weeks
        val RECS = ListSerializer(RecommendationDto.serializer())
    }

    suspend fun upcoming(): Fetched<List<BookDto>> {
        val authors = dao.all().flatMap { it.authors }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(40)
        if (authors.isEmpty()) return Fetched(emptyList())
        return withOfflineCopy("upcoming", ListSerializer(BookDto.serializer())) { api.upcoming(UpcomingRequest(authors)).books }
    }

    /** Series you own part of, with the published volumes you're missing. refresh skips the server's cache. */
    suspend fun seriesGaps(refresh: Boolean = false): Fetched<List<SeriesGapDto>> {
        val owned = dao.all()
        if (owned.isEmpty()) return Fetched(emptyList())
        val body = SeriesRequest(owned.take(500).map { OwnedBookDto(it.title, it.author, it.genres) }, refresh)
        return withOfflineCopy("series", ListSerializer(SeriesGapDto.serializer())) { api.seriesGaps(body).series }
    }

    /**
     * Fetch, and keep a copy. If the fetch fails for any reason and a copy exists, return the copy
     * marked offline instead of an error, so the screen still has something useful to show.
     */
    private suspend fun <T> withOfflineCopy(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): Fetched<T> =
        try {
            fetch().also { offline.save(key, serializer, it) }.let { Fetched(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val saved = offline.load(key, serializer) ?: throw e
            Fetched(saved.data, offlineSince = saved.savedAt)
        }
}

fun BookDto.toEntity() = Book(
    title = title.trim(),
    author = author.trim(),
    genres = genres,
    isbn = isbn,
    coverUrl = coverUrl,
    description = description,
    publishedDate = publishedDate,
    pageCount = pageCount,
)

val BookDto.matchKey: String get() = matchKeyOf(title.trim(), author.trim())

fun BookDto.toWishlistItem(reason: String?) = WishlistItem(
    title = title.trim(),
    author = author.trim(),
    genres = genres,
    isbn = isbn,
    coverUrl = coverUrl,
    description = description,
    publishedDate = publishedDate,
    pageCount = pageCount,
    reason = reason?.takeIf { it.isNotBlank() },
)

fun WishlistItem.toBook() = Book(
    title = title, author = author, genres = genres, isbn = isbn, coverUrl = coverUrl,
    description = description, publishedDate = publishedDate, pageCount = pageCount,
)

fun WishlistItem.toDto() = BookDto(
    title = title, author = author, genres = genres, isbn = isbn, coverUrl = coverUrl,
    description = description, publishedDate = publishedDate, pageCount = pageCount,
)
