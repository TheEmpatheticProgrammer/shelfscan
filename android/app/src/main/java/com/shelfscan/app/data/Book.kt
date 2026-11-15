package com.shelfscan.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books", indices = [Index(value = ["matchKey"], unique = true)])
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val genres: List<String> = emptyList(),
    val isbn: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    /** 2-3 sentence summary for the book page, written once by the server and kept. */
    val synopsis: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val matchKey: String = matchKeyOf(title, author),
) {
    /** First genre, used as the shelf a book sits on when grouping by genre. */
    val primaryGenre: String get() = genres.firstOrNull { !it.equals("Fiction", true) } ?: genres.firstOrNull() ?: "Uncategorized"

    /** Each credited author, for grouping ("A, B" -> [A, B]). */
    val authors: List<String> get() = author.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Unknown") }
}

fun matchKeyOf(title: String, author: String): String =
    (title + author).lowercase().filter { it.isLetterOrDigit() }
