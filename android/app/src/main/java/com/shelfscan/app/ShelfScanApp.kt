package com.shelfscan.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.shelfscan.app.data.AppDatabase
import com.shelfscan.app.data.BookRepository
import com.shelfscan.app.data.OfflineStore
import com.shelfscan.app.data.PendingScanStore
import com.shelfscan.app.data.remote.ShelfApi

class ShelfScanApp : Application(), ImageLoaderFactory {
    val repository: BookRepository by lazy {
        val db = AppDatabase.create(this)
        BookRepository(
            this, db.books(), db.wishlist(), ShelfApi.create(),
            offline = OfflineStore(filesDir.resolve("offline")),
            pendingStore = PendingScanStore(filesDir.resolve("pending")),
        )
    }

    /**
     * Covers are downloaded once and kept: a large disk cache, and cache headers ignored, because a
     * book's cover at a given address never changes. Browsing the library then works offline too.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(300L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()
}
