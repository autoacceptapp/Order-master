package com.example

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Download state model emitted during APK downloading.
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadState()
    data class ReadyToInstall(val apkFile: File) : DownloadState()
    data class Error(val message: String, val throwable: Throwable? = null) : DownloadState()
}

/**
 * Production-ready APK Downloader & Installer utility.
 *
 * Features:
 * - Streams APK download with real-time percentage progress (0% - 100%).
 * - Stores downloaded APK in app-specific external files dir: Context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).
 * - Generates secure content:// URI via FileProvider (${applicationId}.fileprovider).
 * - Checks and requests REQUEST_INSTALL_PACKAGES permission on Android 8.0+ (API 26+).
 * - Triggers Android package installer with FLAG_GRANT_READ_URI_PERMISSION.
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var activeDownloadJob: Job? = null

    /**
     * Checks if the app has permission to install packages (Android 8.0+ / API 26+).
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Creates an Intent to navigate the user to the "Install Unknown Apps" settings page for this app.
     */
    fun createManageUnknownAppSourcesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Downloads an APK file asynchronously from the provided URL, tracking progress in real-time.
     *
     * @param context Application context
     * @param downloadUrl Direct URL to the compiled .apk asset
     * @param fileName Target filename on disk (e.g. "OrderMaster_v2.0.apk")
     */
    fun startDownload(
        context: Context,
        downloadUrl: String,
        fileName: String = "OrderMaster_update.apk",
        coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ) {
        // Cancel any previous download in flight
        activeDownloadJob?.cancel()

        activeDownloadJob = coroutineScope.launch {
            _downloadState.value = DownloadState.Downloading(
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = -1L
            )

            var connection: HttpURLConnection? = null
            try {
                // Prepare destination directory
                val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads").apply { mkdirs() }

                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                // Clean up previous APK files in directory
                targetDir.listFiles { file -> file.name.endsWith(".apk", ignoreCase = true) }
                    ?.forEach { it.delete() }

                val destinationFile = File(targetDir, fileName)
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }

                val url = URL(downloadUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "AutoAccept-APK-Downloader")
                }

                // Handle redirects manually if needed
                var redirectCode = connection.responseCode
                if (redirectCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    redirectCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    redirectCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    redirectCode == 307 || redirectCode == 308
                ) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    connection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("Accept", "application/octet-stream")
                        setRequestProperty("User-Agent", "AutoAccept-APK-Downloader")
                    }
                    redirectCode = connection.responseCode
                }

                if (redirectCode != HttpURLConnection.HTTP_OK) {
                    val err = DownloadState.Error("Server returned HTTP $redirectCode when downloading APK.")
                    _downloadState.value = err
                    return@launch
                }

                val totalLength = connection.contentLength.toLong()
                var downloaded = 0L
                var lastEmittedPercent = -1

                connection.inputStream.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) {
                                destinationFile.delete()
                                return@launch
                            }

                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val percent = if (totalLength > 0) {
                                ((downloaded * 100) / totalLength).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }

                            if (percent != lastEmittedPercent) {
                                lastEmittedPercent = percent
                                _downloadState.value = DownloadState.Downloading(
                                    progressPercent = percent,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalLength
                                )
                            }
                        }
                        output.flush()
                    }
                }

                Log.i(TAG, "Download complete: ${destinationFile.absolutePath} (${destinationFile.length()} bytes)")
                val readyState = DownloadState.ReadyToInstall(destinationFile)
                _downloadState.value = readyState

            } catch (e: Exception) {
                Log.e(TAG, "Exception during APK download", e)
                _downloadState.value = DownloadState.Error(
                    message = "Download failed: ${e.localizedMessage ?: "Unknown error"}",
                    throwable = e
                )
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Cancels any active download and resets the state to Idle.
     */
    fun cancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Resets the downloader state to Idle.
     */
    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Launches the system package installer intent to install the downloaded APK file.
     *
     * @param context Context used to start the install activity
     * @param apkFile The downloaded APK file
     * @return true if the install intent was launched, false if permission is missing or an error occurred
     */
    fun promptInstall(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Log.e(TAG, "Cannot install: File does not exist at ${apkFile.absolutePath}")
            _downloadState.value = DownloadState.Error("Downloaded APK file not found on disk.")
            return false
        }

        // On Android 8.0+ (API 26+), check canRequestPackageInstalls
        if (!canRequestPackageInstalls(context)) {
            Log.w(TAG, "REQUEST_INSTALL_PACKAGES permission not granted. Redirecting to settings.")
            try {
                val intent = createManageUnknownAppSourcesIntent(context)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open install packages settings", e)
            }
            return false
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            Log.i(TAG, "Package installer intent dispatched for URI: $apkUri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            _downloadState.value = DownloadState.Error(
                message = "Unable to launch installer: ${e.localizedMessage}",
                throwable = e
            )
            false
        }
    }
}
