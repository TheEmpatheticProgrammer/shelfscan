package com.shelfscan.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A book you don't own yet but want to read: saved from a suggestion. */
@Entity(tableName = "wishlist", indices = [Index(value = ["matchKey"], unique = true)])
data class WishlistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val genres: List<String> = emptyList(),
    val isbn: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    /** Why it was suggested, kept so the list still makes sense later. */
    val reason: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val matchKey: String = matchKeyOf(title, author),
)
