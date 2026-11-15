package com.shelfscan.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The export file: everything needed to rebuild the library on another phone. Plain JSON so it stays
 * readable and future versions can still import it (unknown fields are ignored, missing ones defaulted).
 */
@Serializable
data class LibraryExport(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val books: List<ExportedBook> = emptyList(),
    val wantToRead: List<ExportedBook> = emptyList(),
) {
    companion object { const val FORMAT = "shelfscan-library" }
}

@Serializable
data class ExportedBook(
    val title: String,
    val author: String,
    val genres: List<String> = emptyList(),
    val isbn: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    val synopsis: String? = null,
    val readingStatus: String = ReadingStatus.UNREAD,
    val reason: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

data class ImportResult(val books: Int, val wantToRead: Int, val skipped: Int)

val backupJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun Book.toExported() = ExportedBook(
    title, author, genres, isbn, coverUrl, description, publishedDate, pageCount,
    synopsis?.takeIf { it.isNotBlank() }, readingStatus, null, addedAt,
)

fun WishlistItem.toExported() = ExportedBook(
    title, author, genres, isbn, coverUrl, description, publishedDate, pageCount, null, ReadingStatus.UNREAD, reason, addedAt,
)

fun ExportedBook.toBook() = Book(
    title = title.trim(), author = author.trim(), genres = genres, isbn = isbn, coverUrl = coverUrl,
    description = description, publishedDate = publishedDate, pageCount = pageCount, synopsis = synopsis,
    readingStatus = readingStatus.takeIf { it in ReadingStatus.all } ?: ReadingStatus.UNREAD, addedAt = addedAt,
)

fun ExportedBook.toWishlistItem() = WishlistItem(
    title = title.trim(), author = author.trim(), genres = genres, isbn = isbn, coverUrl = coverUrl,
    description = description, publishedDate = publishedDate, pageCount = pageCount, reason = reason, addedAt = addedAt,
)
