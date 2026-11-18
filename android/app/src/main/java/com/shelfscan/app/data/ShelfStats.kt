package com.shelfscan.app.data

import java.util.Locale
import kotlin.math.roundToInt

/** The numbers on a shelfie card: "312 books, mostly sci-fi, 41% read". */
data class ShelfStats(
    val books: Int,
    val authors: Int,
    val read: Int,
    val reading: Int,
    /** The most common genre, or null when no book has one. */
    val topGenre: String?,
    /** Share of all books in [topGenre], 0-100. */
    val topGenrePercent: Int,
    /** The author with the most books on the shelf, when that's at least two. */
    val topAuthor: String?,
    val topAuthorCount: Int,
) {
    val readPercent: Int get() = if (books == 0) 0 else (read * 100.0 / books).roundToInt()

    /** "mostly sci-fi" when one genre clearly leads, "a bit of everything" when none does. */
    val genreLine: String?
        get() = when {
            topGenre == null -> null
            topGenrePercent >= MOSTLY_PERCENT -> "mostly ${genreWord(topGenre)}"
            books >= 5 -> "a bit of everything"
            else -> null
        }

    val bookCount: String get() = if (books == 1) "1 book" else String.format(Locale.US, "%,d books", books)

    /** "312 books, mostly sci-fi, 41% read" */
    val headline: String get() = listOfNotNull(bookCount, genreLine, "$readPercent% read").joinToString(", ")

    companion object {
        const val MOSTLY_PERCENT = 30

        fun of(shelf: List<Book>): ShelfStats {
            val genres = shelf.map { it.primaryGenre }.filter { it != "Uncategorized" }
                .groupingBy { it }.eachCount()
            val topGenre = genres.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()
            val authors = shelf.flatMap { it.authors }.filterNot { it.equals("Unknown", ignoreCase = true) }
            val topAuthor = authors.groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.takeIf { it.value >= 2 }
            return ShelfStats(
                books = shelf.size,
                authors = authors.toSet().size,
                read = shelf.count { it.readingStatus == ReadingStatus.READ },
                reading = shelf.count { it.readingStatus == ReadingStatus.READING },
                topGenre = topGenre?.key,
                topGenrePercent = if (shelf.isEmpty() || topGenre == null) 0 else (topGenre.value * 100.0 / shelf.size).roundToInt(),
                topAuthor = topAuthor?.key,
                topAuthorCount = topAuthor?.value ?: 0,
            )
        }

        /** How a genre reads mid-sentence: "Science Fiction" -> "sci-fi", "Fantasy" -> "fantasy". */
        fun genreWord(genre: String): String = when (val g = genre.trim().lowercase(Locale.US)) {
            "science fiction", "sci-fi", "sf", "science-fiction" -> "sci-fi"
            "non-fiction", "nonfiction", "non fiction" -> "nonfiction"
            "young adult", "young adult fiction", "ya" -> "YA"
            "comics & graphic novels", "graphic novels", "comics" -> "comics"
            else -> g
        }

        /** The books whose covers go on the card: ones with a cover first, then what you're reading, then the newest. */
        fun coverPicks(shelf: List<Book>, count: Int = 8): List<Book> = shelf.sortedWith(
            compareByDescending<Book> { it.coverUrl != null }
                .thenByDescending { it.readingStatus == ReadingStatus.READING }
                .thenByDescending { it.addedAt },
        ).take(count)
    }
}
