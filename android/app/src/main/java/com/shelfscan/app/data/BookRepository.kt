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
import com.shelfscan.app.data.remote.ShelfApi
import com.shelfscan.app.data.remote.UpcomingRequest
import com.shelfscan.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class BookRepository(
    private val context: Context,
    private val dao: BookDao,
    private val wishlistDao: WishlistDao,
    private val api: ShelfApi,
) {
    val books: Flow<List<Book>> = dao.observeAll()

    /** Want to read: books you don't own yet, saved from suggestions. */
    val wishlist: Flow<List<WishlistItem>> = wishlistDao.observeAll()
    val wishlistKeys: Flow<Set<String>> = wishlist.map { items -> items.map { it.matchKey }.toSet() }

    private val prefs = context.getSharedPreferences("shelfscan", Context.MODE_PRIVATE)
    private val similarCache = mutableMapOf<String, List<RecommendationDto>>()
    private val coverUpgrade = Mutex()

    fun book(id: Long): Flow<Book?> = dao.observe(id)

    suspend fun ownedKeys(): Set<String> = dao.allKeys().toSet()

    suspend fun identify(photos: List<Uri>): Pair<List<BookDto>, String?> {
        val parts = photos.mapIndexed { i, uri ->
            val bytes = ImageUtils.prepareForUpload(context, uri)
            MultipartBody.Part.createFormData("images", "photo_$i.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
        }
        val res = api.identify(parts)
        return res.books to res.notes
    }

    /** Returns how many were actually added (duplicates are skipped). A book you now own leaves Want to read. */
    suspend fun add(books: List<BookDto>): Int {
        val added = dao.insertAll(books.map { it.toEntity() }).count { it != -1L }
        wishlistDao.deleteByKeys(books.map { it.matchKey })
        return added
    }

    suspend fun setStatus(book: Book, status: String) = dao.setStatus(book.id, status)

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

    /** "I have this book": it moves from Want to read into the library. */
    suspend fun moveToLibrary(item: WishlistItem) {
        dao.insert(item.toBook())
        wishlistDao.deleteByKeys(listOf(item.matchKey))
    }

    // --- Covers ---

    suspend fun coverCandidates(book: Book): List<String> =
        api.coverCandidates(BookRefDto(book.title, book.author, book.isbn)).candidates

    suspend fun setCover(book: Book, url: String) = dao.setCover(book.id, url, System.currentTimeMillis())

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
        if (export == null || export.format != LibraryExport.FORMAT) error("That isn't a ShelfScan library file")
        val books = export.books.filter { it.title.isNotBlank() && it.author.isNotBlank() }.map { it.toBook() }
        val addedBooks = dao.insertAll(books).count { it != -1L }
        val owned = dao.allKeys().toSet()
        val wishes = export.wantToRead.filter { it.title.isNotBlank() }.map { it.toWishlistItem() }.filter { it.matchKey !in owned }
        val addedWishes = wishlistDao.insertAll(wishes).count { it != -1L }
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
    suspend fun loadDetails(book: Book) {
        if (book.synopsis != null) return
        val d = api.bookDetails(DetailsRequest(book.title, book.author, book.isbn, book.description))
        dao.update(
            book.copy(
                coverUrl = d.coverUrl ?: book.coverUrl,
                isbn = book.isbn ?: d.isbn,
                description = book.description ?: d.description,
                // An empty synopsis marks "looked, nothing to summarize" so it isn't asked for again.
                synopsis = d.synopsis ?: "",
                publishedDate = book.publishedDate ?: d.publishedDate,
                pageCount = book.pageCount ?: d.pageCount,
            )
        )
    }

    /** "Similar books" for a book page, remembered for this session. */
    suspend fun similarTo(book: Book): List<RecommendationDto> {
        similarCache[book.matchKey]?.let { return it }
        val owned = dao.all()
        val shelf = owned.sortedByDescending { it.author == book.author }.take(150)
        val recs = api.recommendations(
            RecommendRequest(
                books = shelf.map { OwnedBookDto(it.title, it.author, it.genres) } + wantToReadForServer(),
                limit = 8,
                similarTo = OwnedBookDto(book.title, book.author, book.genres),
            )
        ).recommendations
        similarCache[book.matchKey] = recs
        return recs
    }

    /**
     * One-time pass that swaps the small covers of books scanned before Open Library covers existed
     * for large ones. If the server can't be reached it simply runs again on the next launch.
     */
    suspend fun upgradeCoversOnce() = coverUpgrade.withLock {
        if (prefs.getBoolean(COVERS_V2, false)) return@withLock
        dao.all().chunked(25).forEach { batch ->
            val found = api.covers(CoversRequest(batch.map { BookRefDto(it.title, it.author, it.isbn) })).covers
            batch.zip(found).forEach { (book, cover) ->
                val url = cover.coverUrl
                if (url != null && url != book.coverUrl) dao.update(book.copy(coverUrl = url, isbn = book.isbn ?: cover.isbn))
            }
        }
        prefs.edit().putBoolean(COVERS_V2, true).apply()
    }

    suspend fun delete(book: Book) = dao.delete(book)

    /** Pass an author or a genre to focus the suggestions; neither means "everything I own". */
    suspend fun recommendations(author: String? = null, genre: String? = null, limit: Int = 10): List<RecommendationDto> {
        val owned = dao.all()
        if (owned.isEmpty()) return emptyList()
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
        return api.recommendations(body).recommendations
    }

    /** Books already on Want to read go along too, so they aren't suggested again (and they reflect taste). */
    private suspend fun wantToReadForServer() = wishlistDao.all().take(50).map { OwnedBookDto(it.title, it.author, it.genres) }

    private companion object {
        const val COVERS_V2 = "covers_v2_done"
        const val COVER_RETRY_MS = 3L * 24 * 60 * 60 * 1000 // look again after three days
    }

    suspend fun upcoming(): List<BookDto> {
        val authors = dao.all().flatMap { it.authors }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(40)
        if (authors.isEmpty()) return emptyList()
        return api.upcoming(UpcomingRequest(authors)).books
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
