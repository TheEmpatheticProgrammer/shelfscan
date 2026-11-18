package com.shelfscan.app.util

import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/** Turns network/server failures into something a reader can act on. */
fun Throwable.friendlyMessage(): String = when (this) {
    // OkHttp reports a connect timeout as a SocketTimeoutException too; that means the server was never reached.
    is SocketTimeoutException ->
        if (message?.contains("connect", ignoreCase = true) == true)
            "Can't reach the Shelfsight server. Check the server address and port, and that it's running."
        else "The server took too long. Try fewer photos at once."
    is IOException -> "Can't reach the Shelfsight server. Check your connection and that the server is running."
    is HttpException -> when (code()) {
        401 -> "The server rejected this app's token."
        // The app is newer than the running server (e.g. barcode lookup on a server started before it existed).
        404 -> "The server doesn't know this feature yet. Restart it with the latest code."
        413 -> "That photo is too large."
        415 -> "That image format isn't supported."
        502 -> "The AI model server failed. Check the Shelfsight server log."
        504 -> "The AI model took too long. Try fewer photos at once."
        else -> "Server error (${code()}). Try again in a moment."
    }
    else -> message ?: "Something went wrong."
}
