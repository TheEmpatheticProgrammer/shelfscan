package com.shelfscan.app

import android.app.Application
import com.shelfscan.app.data.AppDatabase
import com.shelfscan.app.data.BookRepository
import com.shelfscan.app.data.remote.ShelfApi

class ShelfScanApp : Application() {
    val repository: BookRepository by lazy {
        BookRepository(this, AppDatabase.create(this).books(), ShelfApi.create())
    }
}
