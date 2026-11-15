package com.shelfscan.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A server answer kept on the phone, and when it was received. */
@Serializable
data class Saved<T>(val savedAt: Long, val data: T)

/** What a screen shows: fresh from the server, or (when it can't be reached) the last saved copy. */
data class Fetched<T>(val data: T, val offlineSince: Long? = null) {
    val isOffline: Boolean get() = offlineSince != null
}

/**
 * Last-known server answers (suggestions, upcoming releases, similar books), one small JSON file
 * each, so those screens still have something to show offline. Not a cache with expiry: every
 * successful fetch overwrites the copy, and it's only read when a fetch fails.
 */
class OfflineStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, fileName(key) + ".tmp")
        tmp.writeText(json.encodeToString(Saved.serializer(serializer), Saved(System.currentTimeMillis(), value)))
        tmp.renameTo(File(dir, fileName(key))) // atomic swap: a crash never leaves a half-written file
    }

    suspend fun <T> load(key: String, serializer: KSerializer<T>): Saved<T>? = withContext(Dispatchers.IO) {
        val file = File(dir, fileName(key))
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(Saved.serializer(serializer), file.readText()) }.getOrNull()
    }

    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"
}
