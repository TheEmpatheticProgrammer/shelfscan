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
}

class Converters {
    @TypeConverter
    fun fromList(list: List<String>): String = list.joinToString(SEP)

    @TypeConverter
    fun toList(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(SEP)

    private companion object { const val SEP = "\u001F" }
}

@Database(entities = [Book::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "shelfscan.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2 adds the saved synopsis. Existing books keep everything; their synopsis starts empty. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN synopsis TEXT")
            }
        }
    }
}
