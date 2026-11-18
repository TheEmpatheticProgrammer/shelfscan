package com.shelfscan.app

import com.shelfscan.app.data.Book
import com.shelfscan.app.data.ReadingStatus
import com.shelfscan.app.data.ShelfStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfStatsTest {
    private var nextId = 1L

    private fun book(
        genre: String? = "Science Fiction",
        author: String = "Author ${nextId}",
        status: String = ReadingStatus.UNREAD,
        cover: String? = "http://cover/$nextId",
    ) = Book(
        id = nextId, title = "Book ${nextId}", author = author, genres = listOfNotNull(genre),
        readingStatus = status, coverUrl = cover, addedAt = nextId++,
    )

    @Test
    fun headlineReadsLikeASentence() {
        val shelf = List(6) { book(status = if (it < 2) ReadingStatus.READ else ReadingStatus.UNREAD) } +
            List(4) { book(genre = "Fantasy") }
        val stats = ShelfStats.of(shelf)
        assertEquals("10 books, mostly sci-fi, 20% read", stats.headline)
        assertEquals(60, stats.topGenrePercent)
    }

    @Test
    fun noLeadingGenreIsABitOfEverything() {
        val shelf = listOf("Fantasy", "Mystery", "History", "Poetry", "Romance").map { book(genre = it) }
        assertEquals("5 books, a bit of everything, 0% read", ShelfStats.of(shelf).headline)
    }

    @Test
    fun smallOrUngenredShelvesSkipTheGenre() {
        assertEquals("1 book, 100% read", ShelfStats.of(listOf(book(genre = null, status = ReadingStatus.READ))).headline)
        assertEquals("0 books, 0% read", ShelfStats.of(emptyList()).headline)
    }

    @Test
    fun bigShelvesGetThousandsSeparators() {
        val stats = ShelfStats.of(emptyList()).copy(books = 1204)
        assertEquals("1,204 books", stats.bookCount)
    }

    @Test
    fun genreWords() {
        assertEquals("sci-fi", ShelfStats.genreWord("Science Fiction"))
        assertEquals("nonfiction", ShelfStats.genreWord("Non-Fiction"))
        assertEquals("YA", ShelfStats.genreWord("Young Adult"))
        assertEquals("historical fiction", ShelfStats.genreWord("Historical Fiction"))
    }

    @Test
    fun countsAuthorsReadingAndTheTopAuthor() {
        val shelf = listOf(
            book(author = "Brandon Sanderson", status = ReadingStatus.READING),
            book(author = "Brandon Sanderson"),
            book(author = "Frank Herbert, Brian Herbert"),
            book(author = "Unknown"),
        )
        val stats = ShelfStats.of(shelf)
        assertEquals(3, stats.authors)   // Sanderson, Frank Herbert, Brian Herbert; Unknown isn't an author
        assertEquals(1, stats.reading)
        assertEquals("Brandon Sanderson" to 2, stats.topAuthor to stats.topAuthorCount)
    }

    @Test
    fun aSingleBookPerAuthorHasNoTopAuthor() =
        assertNull(ShelfStats.of(listOf(book(), book())).topAuthor)

    @Test
    fun coverPicksPreferCoversThenReadingThenNewest() {
        val noCover = book(cover = null)
        val old = book()
        val reading = book(status = ReadingStatus.READING)
        val newest = book()
        val picks = ShelfStats.coverPicks(listOf(noCover, old, reading, newest), count = 3)
        assertEquals(listOf(reading, newest, old), picks)
    }
}
