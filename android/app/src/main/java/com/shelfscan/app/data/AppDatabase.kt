package com.shelfscan.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<Book?>

    @Query("SELECT * FROM books")
    suspend fun all(): List<Book>

    @Query("SELECT matchKey FROM books")
    suspend fun allKeys(): List<String>

    /** Ignores books already on the shelf (same title + author). Returns -1 for skipped rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(books: List<Book>): List<Long>

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: Book): Long

    @Query("UPDATE books SET readingStatus = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE books SET coverUrl = :url, coverCheckedAt = :checkedAt WHERE id = :id")
    suspend fun setCover(id: Long, url: String?, checkedAt: Long)

    /**
     * Books still without a real cover whose last lookup is older than [before] (or never happened).
     * A `default=false` address is Open Library's "only if it exists" ISBN fallback, which usually shows
     * the placeholder, so it counts as missing.
     */
    @Query(
        "SELECT * FROM books WHERE (coverUrl IS NULL OR coverUrl LIKE '%default=false%') " +
            "AND (coverCheckedAt IS NULL OR coverCheckedAt < :before)"
    )
    suspend fun missingCovers(before: Long): List<Book>

    @Query("UPDATE books SET coverCheckedAt = :checkedAt WHERE id IN (:ids)")
    suspend fun markCoverChecked(ids: List<Long>, checkedAt: Long)
}

@Dao
interface WishlistDao {
    @Query("SELECT * FROM wishlist ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WishlistItem>>

    @Query("SELECT * FROM wishlist")
    suspend fun all(): List<WishlistItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WishlistItem>): List<Long>

    @Query("DELETE FROM wishlist WHERE matchKey IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)
}

class Converters {
    @TypeConverter
    fun fromList(list: List<String>): String = list.joinToString(SEP)

    @TypeConverter
    fun toList(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(SEP)

    private companion object { const val SEP = "\u001F" }
}

@Database(entities = [Book::class, WishlistItem::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun wishlist(): WishlistDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "shelfscan.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** v2 adds the saved synopsis. Existing books keep everything; their synopsis starts empty. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN synopsis TEXT")
            }
        }

        /** v3 adds reading status, the cover-retry timestamp and the Want to read list. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN readingStatus TEXT NOT NULL DEFAULT 'unread'")
                db.execSQL("ALTER TABLE books ADD COLUMN coverCheckedAt INTEGER")
                // Must match what Room generates for WishlistItem exactly, or Room rejects the migrated database.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wishlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `author` TEXT NOT NULL, `genres` TEXT NOT NULL, `isbn` TEXT, " +
                        "`coverUrl` TEXT, `description` TEXT, `publishedDate` TEXT, `pageCount` INTEGER, " +
                        "`reason` TEXT, `addedAt` INTEGER NOT NULL, `matchKey` TEXT NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wishlist_matchKey` ON `wishlist` (`matchKey`)")
            }
        }
    }
}
