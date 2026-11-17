package com.example

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
 * Robust APK Downloader & Installer utilizing Android's system DownloadManager.
 *
 * Requirements fulfilled:
 * - Uses DownloadManager.Request to download direct .apk asset URL.
 * - Sets MIME type to "application/vnd.android.package-archive".
 * - Stores downloaded APK in app-specific external files dir (Context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)).
 * - Manages runtime storage/installer checks (REQUEST_INSTALL_PACKAGES for Android 8.0+ / 12+).
 * - Implements dynamic and static BroadcastReceivers for ACTION_DOWNLOAD_COMPLETE.
 * - Triggers installation intent (ACTION_VIEW with FileProvider URI & FLAG_GRANT_READ_URI_PERMISSION).
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"
    internal const val PREFS_NAME = GitHubUpdateManager.PREFS_NAME
    private const val LEGACY_PREFS_NAME = "apk_downloader_prefs"
    internal const val KEY_ACTIVE_DOWNLOAD_ID = "active_download_id"

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var activeDownloadId: Long = -1L
    private var activeFileName: String = GitHubUpdateManager.resolveFallbackApkFileName()
    private var progressPollingJob: Job? = null
    private var downloadCompleteReceiver: BroadcastReceiver? = null
    private val completionLock = Any()
    private val completedDownloadIds = mutableSetOf<Long>()

    /**
     * Checks if the app has permission to install unknown packages (Android 8.0+ / API 26+).
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Creates an Intent navigating the user to the "Install Unknown Apps" settings page.
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
     * Builds the installation Intent with FileProvider URI for the downloaded APK.
     */
    fun getInstallIntent(context: Context, apkFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Launches the system package installer intent for the specified APK file.
     *
     * @return true if installation intent was successfully launched.
     */
    fun promptInstall(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "Cannot install: APK file does not exist or is empty at ${apkFile.absolutePath}")
            _downloadState.value = DownloadState.Error("Downloaded APK file not found on disk.")
            return false
        }

        // Check install unknown packages permission on Android 8.0+ (API 26+)
        if (!canRequestPackageInstalls(context)) {
            Log.w(TAG, "REQUEST_INSTALL_PACKAGES permission not granted. Redirecting to settings.")
            try {
                val settingsIntent = createManageUnknownAppSourcesIntent(context)
                context.startActivity(settingsIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open install packages settings", e)
            }
            return false
        }

        return try {
            val installIntent = getInstallIntent(context, apkFile)
            context.startActivity(installIntent)
            Log.i(TAG, "Package installer intent dispatched for: ${apkFile.name} (${apkFile.length()} bytes)")
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

    internal fun isCompatibleApkForBuildType(
        downloadUrl: String,
        fileName: String,
        isDebugBuild: Boolean = BuildConfig.DEBUG
    ): Boolean {
        val urlAssetName = downloadUrl.substringAfterLast('/').substringBefore('?').trim()
        val containsDebug = fileName.contains("debug", ignoreCase = true) ||
            urlAssetName.contains("debug", ignoreCase = true)
        val containsRelease = fileName.contains("release", ignoreCase = true) ||
            urlAssetName.contains("release", ignoreCase = true)

        return if (!isDebugBuild) {
            !containsDebug
        } else {
            !containsRelease
        }
    }

    /**
     * Downloads an APK file using system DownloadManager.
     *
     * @param context Application context
     * @param downloadUrl Direct URL to the .apk asset
     * @param fileName Target filename on disk (default matches BuildConfig.DEBUG)
     */
    fun startDownload(
        context: Context,
        downloadUrl: String,
        fileName: String = GitHubUpdateManager.resolveFallbackApkFileName(),
        coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ) {
        val appContext = context.applicationContext
        if (!isCompatibleApkForBuildType(downloadUrl, fileName)) {
            val buildType = if (BuildConfig.DEBUG) "debug" else "release"
            Log.e(TAG, "Blocked incompatible APK download '$fileName' ($downloadUrl) on $buildType build.")
            _downloadState.value = DownloadState.Error(
                "Incompatible APK update ($fileName) for this $buildType build."
            )
            return
        }
        activeFileName = fileName

        // Cancel previous download or polling job
        cancelDownload(appContext)
        synchronized(completionLock) {
            completedDownloadIds.clear()
        }

        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            _downloadState.value = DownloadState.Error("System DownloadManager service is unavailable on this device.")
            return
        }

        try {
            // Clean up any existing file
            val targetDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(appContext.filesDir, "downloads").apply { mkdirs() }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val targetFile = File(targetDir, fileName)
            if (targetFile.exists()) {
                targetFile.delete()
            }

            Log.i(TAG, "Starting DownloadManager request for URL: $downloadUrl -> destination: ${targetFile.absolutePath}")

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Order Master Update")
                setDescription("Downloading APK update ($fileName)...")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                // App-specific external files dir works seamlessly on all Android versions
                setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val downloadId = downloadManager.enqueue(request)
            activeDownloadId = downloadId
            saveActiveDownload(appContext, downloadId, fileName)

            _downloadState.value = DownloadState.Downloading(
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = -1L
            )

            // Register dynamic receiver for download completion
            registerReceiver(appContext)

            // Start coroutine polling for smooth UI progress bar updates
            startProgressPolling(appContext, downloadManager, downloadId, coroutineScope)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download via DownloadManager", e)
            _downloadState.value = DownloadState.Error(
                message = "Failed to start download: ${e.localizedMessage ?: "Unknown error"}",
                throwable = e
            )
        }
    }

    /**
     * Periodically queries the system DownloadManager cursor to update progress percentage in UI.
     */
    private fun startProgressPolling(
        context: Context,
        downloadManager: DownloadManager,
        downloadId: Long,
        coroutineScope: CoroutineScope
    ) {
        progressPollingJob?.cancel()
        progressPollingJob = coroutineScope.launch {
            var isFinished = false
            var lastNotifiedPercent = -1
            var lastNotifyTimeMs = 0L
            while (!isFinished && isActive) {
                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    downloadManager.query(query)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                            val status = if (statusCol != -1) cursor.getInt(statusCol) else -1
                            val downloadedBytes = if (bytesCol != -1) cursor.getLong(bytesCol) else 0L
                            val totalBytes = if (totalCol != -1) cursor.getLong(totalCol) else -1L

                            val percent = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }

                            when (status) {
                                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                                    _downloadState.value = DownloadState.Downloading(
                                        progressPercent = percent,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes
                                    )
                                    val nowMs = System.currentTimeMillis()
                                    val percentDelta = if (percent >= 0 && lastNotifiedPercent >= 0) {
                                        kotlin.math.abs(percent - lastNotifiedPercent)
                                    } else {
                                        5
                                    }
                                    if (lastNotifiedPercent == -1 || percentDelta >= 5 || nowMs - lastNotifyTimeMs >= 1000L) {
                                        lastNotifiedPercent = percent
                                        lastNotifyTimeMs = nowMs
                                        UpdateNotificationManager.showDownloadProgressNotification(
                                            context = context,
                                            progressPercent = percent,
                                            downloadedBytes = downloadedBytes,
                                            totalBytes = totalBytes
                                        )
                                    }
                                }
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    isFinished = true
                                    handleExternalDownloadComplete(context, downloadId)
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    isFinished = true
                                    val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    val reason = if (reasonCol != -1) cursor.getInt(reasonCol) else -1
                                    val errorMsg = mapDownloadErrorReason(reason)
                                    Log.e(TAG, "Download failed: reason code $reason ($errorMsg)")
                                    _downloadState.value = DownloadState.Error(errorMsg)
                                }
                            }
                        } else {
                            // Download record removed from DownloadManager
                            isFinished = true
                            handleDownloadRemoved(context)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying DownloadManager status", e)
                }

                delay(500)
            }
        }
    }

    internal fun handleDownloadRemoved(context: Context) {
        activeDownloadId = -1L
        clearSavedDownload(context)
        unregisterReceiver(context)
        _downloadState.value = DownloadState.Error("Download cancelled or removed.")
    }

    /**
     * Strictly verifies that [downloadId] matches an active in-memory or persisted
     * APK download started by [ApkDownloader]. Unrequested/unknown IDs return false
     * even when [activeDownloadId] is -1L (e.g. cold-start).
     */
    fun isExpectedDownloadId(context: Context, downloadId: Long): Boolean {
        if (downloadId <= 0L) return false
        val currentActiveId = activeDownloadId
        val savedId = getSavedDownloadId(context)
        return (currentActiveId > 0L && downloadId == currentActiveId) ||
            (savedId > 0L && downloadId == savedId)
    }

    /**
     * Handles ACTION_DOWNLOAD_COMPLETE from dynamic or manifest broadcast receiver.
     */
    fun handleExternalDownloadComplete(context: Context, downloadId: Long) {
        if (!isExpectedDownloadId(context, downloadId)) {
            Log.w(TAG, "Ignoring unrequested or mismatched download ID: $downloadId")
            return
        }
        synchronized(completionLock) {
            if (!completedDownloadIds.add(downloadId)) {
                return
            }
        }

        progressPollingJob?.cancel()
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        var reachedTerminalState = false

        try {
            val query = DownloadManager.Query().setFilterById(downloadId)
            downloadManager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (statusCol != -1) cursor.getInt(statusCol) else -1

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        reachedTerminalState = true
                        val fileName = getSavedFileName(context).ifBlank { activeFileName }
                        val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        val apkFile = File(targetDir, fileName)

                        if (apkFile.exists() && apkFile.length() > 0) {
                            Log.i(TAG, "Download finished successfully: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
                            GitHubUpdateManager.clearCachedUpdate(context)
                            _downloadState.value = DownloadState.ReadyToInstall(apkFile)

                            // Post notification and prompt installer only when app is in foreground
                            UpdateNotificationManager.showDownloadCompleteNotification(context, apkFile)
                            if (isAppInForeground(context)) {
                                promptInstall(context, apkFile)
                            } else {
                                Log.i(TAG, "App is in background; install prompt deferred to notification tap.")
                            }
                        } else {
                            Log.w(TAG, "STATUS_SUCCESSFUL received, but file at ${apkFile.absolutePath} is missing or 0 bytes.")
                            _downloadState.value = DownloadState.Error("Downloaded APK file could not be verified.")
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        reachedTerminalState = true
                        val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val reason = if (reasonCol != -1) cursor.getInt(reasonCol) else -1
                        val errorMsg = mapDownloadErrorReason(reason)
                        Log.e(TAG, "Download failed: $errorMsg (code $reason)")
                        _downloadState.value = DownloadState.Error(errorMsg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception handling download complete", e)
            _downloadState.value = DownloadState.Error("Error finalizing downloaded file: ${e.localizedMessage}")
        } finally {
            if (reachedTerminalState) {
                activeDownloadId = -1L
                clearSavedDownload(context)
            }
            // Cleanly unregister receiver when download reaches a terminal state to prevent leaks
            unregisterReceiver(context)
        }
    }

    private fun isAppInForeground(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val myPid = android.os.Process.myPid()
            am.runningAppProcesses?.any { proc ->
                proc.pid == myPid &&
                    proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private var registeredContext: Context? = null

    private fun registerReceiver(context: Context) {
        val appContext = context.applicationContext
        if (downloadCompleteReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val targetCtx = recvContext?.applicationContext ?: appContext
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (isExpectedDownloadId(targetCtx, id)) {
                        handleExternalDownloadComplete(targetCtx, id)
                    }
                }
            }
        }

        downloadCompleteReceiver = receiver
        registeredContext = appContext
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register download receiver", e)
        }
    }

    /**
     * Safely unregisters the dynamic broadcast receiver avoiding Activity/Context leaks.
     */
    fun unregisterReceiver(context: Context? = null) {
        val targetContext = context?.applicationContext ?: registeredContext
        downloadCompleteReceiver?.let { receiver ->
            try {
                targetContext?.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Safe unregister receiver suppressed exception: ${e.message}")
            }
            downloadCompleteReceiver = null
            registeredContext = null
        }
    }

    /**
     * Cancels active download and cleans up jobs & receivers.
     */
    fun cancelDownload(context: Context? = null) {
        progressPollingJob?.cancel()
        progressPollingJob = null

        val targetContext = context?.applicationContext ?: registeredContext

        if (activeDownloadId > 0 && targetContext != null) {
            val dm = targetContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            try {
                dm?.remove(activeDownloadId)
            } catch (_: Exception) {
            }
        }

        unregisterReceiver(targetContext)
        if (targetContext != null) {
            clearSavedDownload(targetContext)
        }

        activeDownloadId = -1L
        synchronized(completionLock) {
            completedDownloadIds.clear()
        }
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Public cleanup method invoked during app lifecycle end or dialog dismissal.
     */
    fun cleanup(context: Context? = null) {
        progressPollingJob?.cancel()
        progressPollingJob = null
        unregisterReceiver(context)
    }

    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }

    private fun saveActiveDownload(context: Context, downloadId: Long, fileName: String) {
        cleanupLegacyDownloadPrefs(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ACTIVE_DOWNLOAD_ID, downloadId)
            .putString(GitHubUpdateManager.KEY_CACHED_APK_FILENAME, fileName)
            .apply()
    }

    private fun getSavedDownloadId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_ACTIVE_DOWNLOAD_ID, -1L)
    }

    private fun getSavedFileName(context: Context): String {
        val fallback = GitHubUpdateManager.resolveFallbackApkFileName()
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(GitHubUpdateManager.KEY_CACHED_APK_FILENAME, fallback) ?: fallback
    }

    private fun clearSavedDownload(context: Context) {
        cleanupLegacyDownloadPrefs(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_DOWNLOAD_ID)
            .apply()
    }

    private fun cleanupLegacyDownloadPrefs(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.applicationContext.deleteSharedPreferences(LEGACY_PREFS_NAME)
            }
        } catch (_: Throwable) {}
    }

    private fun mapDownloadErrorReason(reason: Int): String {
        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Download cannot be resumed."
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "No external storage device was found."
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Destination file already exists."
            DownloadManager.ERROR_FILE_ERROR -> "Storage device file error."
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data parsing error from server."
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient device storage space."
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many HTTP redirects from server."
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP response code."
            DownloadManager.ERROR_UNKNOWN -> "Unknown download error."
            else -> "Download failed with error code $reason."
        }
    }
}
