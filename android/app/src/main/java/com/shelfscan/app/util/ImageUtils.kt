package com.shelfscan.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {
    private const val MAX_EDGE = 1600 // plenty for reading spines; keeps uploads well under the 5 MB limit

    /** A fresh file URI for the camera to write into. */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "shelf_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Decode, downscale, apply EXIF rotation, and re-encode as JPEG. */
    suspend fun prepareForUpload(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2

        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Couldn't read that image")

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        ByteArrayOutputStream().use { stream ->
            out.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            if (out !== decoded) out.recycle()
            decoded.recycle()
            stream.toByteArray()
        }
    }
}
