package com.skycommand.relay.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Only present in the opt-in landingDiagnostic APK. Never owns or calls a DJI manager. */
class LandingProbeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        thread(name = "landing-probe-loader", isDaemon = true) {
            runCatching {
                val directory = File(app.noBackupFilesDir, "landing-probe")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostic directory" }
                // At most four prior sessions, plus this one; each native capture stops at 16 MiB.
                directory.listFiles { file -> file.name.startsWith("events-") && file.extension == "jsonl" }
                    ?.sortedByDescending(File::lastModified)?.drop(4)?.forEach { file ->
                        check(file.delete()) { "Cannot rotate diagnostic capture" }
                    }
                val nativeDirectory = File(app.applicationInfo.nativeLibraryDir)
                val sdk = File(nativeDirectory, "libdjisdk_jni.so")
                val digest = MessageDigest.getInstance("SHA-256")
                sdk.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        digest.update(buffer, 0, length)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(hash == SDK_SHA256) { "Unsupported DJI native library; probes disabled" }
                System.load(File(nativeDirectory, "liblandingprobe.so").absolutePath)
                Log.i(TAG, "Gadget loaded; wait for READY before using capture")
            }.onFailure { Log.e(TAG, "Landing probes disabled", it) }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "LandingNativeProbe"
        const val SDK_SHA256 = "9e0c7adbd803bbbd1d843e04c5e69119c812b1abb27f12dc89ea04114396eb5c"
    }
}
