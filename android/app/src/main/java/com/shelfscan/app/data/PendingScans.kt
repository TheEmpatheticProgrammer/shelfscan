package com.shelfscan.app.data

import com.shelfscan.app.data.remote.BookDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Photos taken while the server couldn't be reached, waiting to be read (or read, waiting for review). */
@Serializable
data class PendingScan(
    val id: String,
    val createdAt: Long,
    val photoCount: Int,
    val state: String = QUEUED,
    /** What the server found, once read. */
    val books: List<BookDto> = emptyList(),
    val notes: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    val isReady get() = state == READY
    val isFailed get() = state == FAILED

    companion object {
        const val QUEUED = "queued" // waiting for the server
        const val READY = "ready"   // read; waiting for the user to review
        const val FAILED = "failed" // the server rejected the photos; retrying won't help
    }
}

/**
 * The scan queue on disk: one folder per scan holding its (already downscaled) photos and a
 * `scan.json`. Files rather than the database, so photos and their state live and die together.
 */
class PendingScanStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val lock = Mutex()
    private val _scans = MutableStateFlow<List<PendingScan>>(emptyList())
    val scans: StateFlow<List<PendingScan>> = _scans.asStateFlow()

    suspend fun refresh() = lock.withLock { publish() }

    suspend fun enqueue(photos: List<ByteArray>): PendingScan = lock.withLock {
        withContext(Dispatchers.IO) {
            val scan = PendingScan(UUID.randomUUID().toString(), System.currentTimeMillis(), photos.size)
            val dir = File(root, scan.id).apply { mkdirs() }
            photos.forEachIndexed { i, bytes -> File(dir, "photo_$i.jpg").writeBytes(bytes) }
            write(scan)
            publish()
            scan
        }
    }

    suspend fun photos(id: String): List<ByteArray> = withContext(Dispatchers.IO) {
        File(root, id).listFiles { f -> f.name.startsWith("photo_") }.orEmpty().sortedBy { it.name }.map { it.readBytes() }
    }

    suspend fun update(id: String, change: (PendingScan) -> PendingScan) = lock.withLock {
        withContext(Dispatchers.IO) {
            read(id)?.let { write(change(it)) }
            publish()
        }
    }

    suspend fun remove(id: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            File(root, id).deleteRecursively()
            publish()
        }
    }

    private fun read(id: String): PendingScan? =
        runCatching { json.decodeFromString(PendingScan.serializer(), File(File(root, id), META).readText()) }.getOrNull()

    private fun write(scan: PendingScan) {
        val dir = File(root, scan.id)
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(json.encodeToString(PendingScan.serializer(), scan))
        tmp.renameTo(File(dir, META))
    }

    private suspend fun publish() = withContext(Dispatchers.IO) {
        _scans.value = root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { read(it.name) }
            .sortedBy { it.createdAt }
    }

    private companion object { const val META = "scan.json" }
}
