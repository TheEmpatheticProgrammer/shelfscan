package com.shelfscan.app.data

import android.content.Context
import android.net.Uri
import com.shelfscan.app.data.remote.BookDto
import com.shelfscan.app.data.remote.BookRefDto
import com.shelfscan.app.data.remote.CoversRequest
import com.shelfscan.app.data.remote.DetailsRequest
import com.shelfscan.app.data.remote.OwnedBookDto
import com.shelfscan.app.data.remote.RecommendRequest
import com.shelfscan.app.data.remote.RecommendationDto
import com.shelfscan.app.data.remote.ShelfApi
import com.shelfscan.app.data.remote.UpcomingRequest
import com.shelfscan.app.util.ImageUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class BookRepository(
    private val context: Context,
    private val dao: BookDao,
    private val api: ShelfApi,
) {
    val books: Flow<List<Book>> = dao.observeAll()

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

    /** Returns how many were actually added (duplicates are skipped). */
    suspend fun add(books: List<BookDto>): Int =
        dao.insertAll(books.map { it.toEntity() }).count { it != -1L }

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
                books = shelf.map { OwnedBookDto(it.title, it.author, it.genres) },
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
            books = shelf.map { OwnedBookDto(it.title, it.author, it.genres) },
            limit = limit,
            author = author,
            genre = genre,
        )
        return api.recommendations(body).recommendations
    }

    private companion object { const val COVERS_V2 = "covers_v2_done" }

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
