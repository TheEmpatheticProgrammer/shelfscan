package com.shelfscan.app.util

import android.content.Context
import android.util.Log
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_CANCELED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_FAILED
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Google's code scanner (its own camera screen, no camera permission) set up for book barcodes.
 *
 * The scanner is a Google Play services module downloaded on demand. The manifest's
 * `com.google.mlkit.vision.DEPENDENCIES` entry only pre-downloads it for apps installed from the Play
 * Store, so for an Android Studio install it was missing and the first scans failed. This class asks
 * Play services for the module explicitly, waits for it, and only then opens the scanner.
 */
class BookBarcodeScanner(context: Context) {
    private val scanner = GmsBarcodeScanning.getClient(
        context,
        GmsBarcodeScannerOptions.Builder()
            // Books carry EAN-13 (978/979...); UPC-A and EAN-8 are read so we can say "that's not an ISBN".
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_EAN_8)
            .build(),
    )
    private val modules = ModuleInstall.getClient(context)

    /** Start the download in the background if the module isn't on the phone yet (no-op if it is). */
    fun prepare() {
        modules.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
            .addOnFailureListener { Log.w(TAG, "Couldn't request the barcode scanner module", it) }
    }

    /**
     * Open the scanner, downloading it first if needed.
     * [onWaiting] is told when a download is in progress; [onCode] gets the barcode's digits.
     */
    fun scan(onCode: (String) -> Unit, onWaiting: (String) -> Unit, onError: (String) -> Unit) {
        modules.areModulesAvailable(scanner)
            .addOnSuccessListener { if (it.areModulesAvailable()) launchScanner(onCode, onError) else installThenLaunch(onCode, onWaiting, onError) }
            .addOnFailureListener {
                Log.w(TAG, "Couldn't check the barcode scanner module; trying to open it anyway", it)
                launchScanner(onCode, onError)
            }
    }

    private fun installThenLaunch(onCode: (String) -> Unit, onWaiting: (String) -> Unit, onError: (String) -> Unit) {
        onWaiting("Getting the barcode scanner ready (a one-time download)…")
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                when (update.installState) {
                    STATE_COMPLETED -> { modules.unregisterListener(this); launchScanner(onCode, onError) }
                    STATE_FAILED, STATE_CANCELED -> {
                        modules.unregisterListener(this)
                        onError("Couldn't download the barcode scanner. Check your connection and that Google Play services is up to date.")
                    }
                }
            }
        }
        modules.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build())
            .addOnSuccessListener { response ->
                if (response.areModulesAlreadyInstalled()) { modules.unregisterListener(listener); launchScanner(onCode, onError) }
            }
            .addOnFailureListener {
                Log.w(TAG, "Barcode scanner module install failed", it)
                modules.unregisterListener(listener)
                onError("Couldn't download the barcode scanner (${it.message ?: it.javaClass.simpleName}).")
            }
    }

    private fun launchScanner(onCode: (String) -> Unit, onError: (String) -> Unit) {
        scanner.startScan()
            .addOnSuccessListener { code ->
                val value = code.rawValue
                if (value.isNullOrBlank()) onError("Couldn't read that barcode. Try again with the barcode filling more of the frame.")
                else onCode(value)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Barcode scan failed", e)
                onError(
                    if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE)
                        "The barcode scanner is still downloading. Try again in a minute."
                    // Say what actually went wrong instead of a generic message, so problems can be fixed.
                    else "Couldn't start the barcode scanner (${e.message ?: e.javaClass.simpleName})."
                )
            }
        // Backing out of the scanner (canceled) needs no message.
    }

    private companion object { const val TAG = "ShelfScan" }
}
