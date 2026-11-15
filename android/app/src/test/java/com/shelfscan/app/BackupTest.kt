package com.shelfscan.app

import com.shelfscan.app.data.Book
import com.shelfscan.app.data.LibraryExport
import com.shelfscan.app.data.ReadingStatus
import com.shelfscan.app.data.WishlistItem
import com.shelfscan.app.data.backupJson
import com.shelfscan.app.data.toBook
import com.shelfscan.app.data.toExported
import com.shelfscan.app.data.toWishlistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    private fun roundTrip(export: LibraryExport): LibraryExport =
        backupJson.decodeFromString(LibraryExport.serializer(), backupJson.encodeToString(LibraryExport.serializer(), export))

    @Test
    fun booksAndWantToReadSurviveARoundTrip() {
        val book = Book(
            title = "Dune", author = "Frank Herbert", genres = listOf("Science Fiction"), isbn = "9780441013593",
            coverUrl = "https://covers.openlibrary.org/b/id/1-L.jpg", synopsis = "A desert world.",
            readingStatus = ReadingStatus.READING, addedAt = 1_000L,
        )
        val wish = WishlistItem(title = "Hyperion", author = "Dan Simmons", reason = "Epic SF", addedAt = 2_000L)
        val back = roundTrip(LibraryExport(books = listOf(book.toExported()), wantToRead = listOf(wish.toExported())))

        val restored = back.books.single().toBook()
        assertEquals(book.title, restored.title)
        assertEquals(book.author, restored.author)
        assertEquals(book.isbn, restored.isbn)
        assertEquals(book.coverUrl, restored.coverUrl)
        assertEquals(book.synopsis, restored.synopsis)
        assertEquals(ReadingStatus.READING, restored.readingStatus)
        assertEquals(1_000L, restored.addedAt)
        assertEquals(book.matchKey, restored.matchKey)

        val restoredWish = back.wantToRead.single().toWishlistItem()
        assertEquals("Hyperion", restoredWish.title)
        assertEquals("Epic SF", restoredWish.reason)
    }

    @Test
    fun emptySynopsisMarkerIsNotExported() {
        // "" means "looked, nothing to summarize"; the new phone should look again.
        assertNull(Book(title = "A", author = "B", synopsis = "").toExported().synopsis)
    }

    @Test
    fun unknownStatusAndExtraFieldsAreTolerated() {
        val json = """
            {"format": "shelfscan-library", "version": 2, "futureField": true,
             "books": [{"title": "Emma", "author": "Jane Austen", "readingStatus": "abandoned", "rating": 5}]}
        """.trimIndent()
        val export = backupJson.decodeFromString(LibraryExport.serializer(), json)
        val book = export.books.single().toBook()
        assertEquals("Emma", book.title)
        assertEquals(ReadingStatus.UNREAD, book.readingStatus)
    }
}
