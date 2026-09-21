package com.example

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Result sealed class representing the outcome of checking for new updates on GitHub.
 */
sealed class UpdateResult {
    data object Idle : UpdateResult()
    data object Checking : UpdateResult()
    data class UpdateAvailable(
        val latestVersion: String,
        val latestVersionCode: Int = 0,
        val releaseNotes: String,
        val downloadUrl: String,
        val apkFileName: String = "app-debug.apk",
        val assetSize: Long = 0L,
        val publishedAt: String = ""
    ) : UpdateResult()
    data class NoUpdate(val currentVersion: String) : UpdateResult()
    data class Error(val message: String, val throwable: Throwable? = null) : UpdateResult()
}

/**
 * Utility to check for new releases directly from the GitHub Releases API.
 * Target Repository: https://github.com/autoacceptapp/Order-master
 * Release API Endpoint: https://api.github.com/repos/autoacceptapp/Order-master/releases/latest
 */
object GitHubUpdateManager {

    private const val TAG = "GitHubUpdateManager"
    private const val GITHUB_OWNER = "autoacceptapp"
    private const val GITHUB_REPO = "Order-master"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private const val PREFS_NAME = "github_update_prefs"
    private const val KEY_SKIPPED_VERSION = "key_skipped_version"
    private const val KEY_LAST_CHECK_TIME = "key_last_check_time"
    private const val KEY_CACHED_VERSION = "key_cached_version"
    private const val KEY_CACHED_VERSION_CODE = "key_cached_version_code"
    private const val KEY_CACHED_RELEASE_NOTES = "key_cached_release_notes"
    private const val KEY_CACHED_DOWNLOAD_URL = "key_cached_download_url"
    private const val KEY_CACHED_APK_FILENAME = "key_cached_apk_filename"
    private const val KEY_CACHED_ASSET_SIZE = "key_cached_asset_size"

    // 15 minutes throttle window to prevent aggressive GitHub API rate limiting
    const val CHECK_THROTTLE_WINDOW_MS = 15 * 60 * 1000L

    private val _updateState = MutableStateFlow<UpdateResult>(UpdateResult.Idle)
    val updateState: StateFlow<UpdateResult> = _updateState.asStateFlow()

    private val _isDialogVisible = MutableStateFlow(false)
    val isDialogVisible: StateFlow<Boolean> = _isDialogVisible.asStateFlow()

    /**
     * Automatic update checker called directly from MainActivity.onCreate().
     */
    fun autoCheckForUpdates(
        context: Context,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        force: Boolean = false
    ) {
        scope.launch {
            val lastCheck = getLastCheckTime(context)
            val now = System.currentTimeMillis()
            val timeSinceLastCheck = now - lastCheck

            val skippedVersion = getSkippedVersion(context)
            val cachedVersion = getCachedVersion(context)

            // If checked recently and not forced, check if cached update is still relevant
            if (!force && timeSinceLastCheck < CHECK_THROTTLE_WINDOW_MS && timeSinceLastCheck >= 0) {
                if (cachedVersion.isNotEmpty() && !cachedVersion.equals(skippedVersion, ignoreCase = true)) {
                    val cachedInfo = getCachedUpdateAvailable(context)
                    if (cachedInfo != null && isVersionNewer(
                            remoteVersion = cachedInfo.latestVersion,
                            currentVersion = BuildConfig.VERSION_NAME,
                            currentVersionCode = BuildConfig.VERSION_CODE
                        )
                    ) {
                        Log.i(TAG, "Restoring cached update from SharedPreferences: ${cachedInfo.latestVersion} (code: ${cachedInfo.latestVersionCode})")
                        _updateState.value = cachedInfo
                        _isDialogVisible.value = true
                        UpdateNotificationManager.showUpdateAvailableNotification(
                            context = context,
                            latestVersion = cachedInfo.latestVersion,
                            releaseNotes = cachedInfo.releaseNotes
                        )
                        return@launch
                    }
                }
                Log.d(TAG, "Skipping automatic update check; checked ${timeSinceLastCheck / 1000}s ago.")
                return@launch
            }

            // Perform actual network check
            checkForUpdates(
                context = context,
                currentVersion = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE,
                ignoreSkipped = false,
                notifySystem = true
            )
        }
    }

    /**
     * Checks for updates against the GitHub Releases API asynchronously.
     * Compares latest release's tag_name or versionCode against BuildConfig.VERSION_CODE.
     * Triggers update UI ONLY if latestVersionCode > BuildConfig.VERSION_CODE.
     */
    suspend fun checkForUpdates(
        context: Context,
        currentVersion: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        ignoreSkipped: Boolean = false,
        notifySystem: Boolean = true
    ): UpdateResult = withContext(Dispatchers.IO) {
        _updateState.value = UpdateResult.Checking

        var connection: HttpURLConnection? = null
        try {
            val url = URL(LATEST_RELEASE_API_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "OrderMaster-Android-Updater/$currentVersion (code $currentVersionCode)")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                val noUpdate = UpdateResult.NoUpdate(currentVersion)
                _updateState.value = noUpdate
                _isDialogVisible.value = false
                clearCachedUpdate(context)
                saveLastCheckTime(context)
                return@withContext noUpdate
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream
                val errorMsg = errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                val err = UpdateResult.Error("GitHub API returned HTTP $responseCode: $errorMsg")
                _updateState.value = err
                return@withContext err
            }

            val responseJson = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(responseJson)

            val tagName = json.optString("tag_name", "").trim()
            val releaseTitle = json.optString("name", "").trim()
            val releaseNotes = json.optString("body", "Bug fixes and performance improvements.").trim()
            val publishedAt = json.optString("published_at", "")

            if (tagName.isEmpty()) {
                val err = UpdateResult.Error("Invalid GitHub release: tag_name is missing")
                _updateState.value = err
                return@withContext err
            }

            // Inspect assets array to find the compiled APK asset (.apk)
            val assetsArray: JSONArray? = json.optJSONArray("assets")
            var apkDownloadUrl: String? = null
            var apkFileName = "app-debug.apk"
            var assetSize = 0L

            if (assetsArray != null && assetsArray.length() > 0) {
                var fallbackApkUrl: String? = null
                var fallbackApkName: String? = null
                var fallbackSize = 0L

                for (i in 0 until assetsArray.length()) {
                    val assetObj = assetsArray.getJSONObject(i)
                    val name = assetObj.optString("name", "")
                    val downloadUrl = assetObj.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                        // Prefer exact "app-debug.apk" direct downloadable name
                        if (name.equals("app-debug.apk", ignoreCase = true)) {
                            apkDownloadUrl = downloadUrl
                            apkFileName = name
                            assetSize = assetObj.optLong("size", 0L)
                            break
                        } else if (fallbackApkUrl == null) {
                            fallbackApkUrl = downloadUrl
                            fallbackApkName = name
                            fallbackSize = assetObj.optLong("size", 0L)
                        }
                    }
                }

                if (apkDownloadUrl == null && fallbackApkUrl != null) {
                    apkDownloadUrl = fallbackApkUrl
                    apkFileName = fallbackApkName ?: "app-debug.apk"
                    assetSize = fallbackSize
                }
            }

            // Fallback direct URL format if no explicit asset was indexed yet in the array
            if (apkDownloadUrl.isNullOrEmpty()) {
                apkDownloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/app-debug.apk"
                apkFileName = "app-debug.apk"
            }

            // Save last checked timestamp
            saveLastCheckTime(context)

            // Extract numeric version code from tag name (e.g. "debug-apk-build-12-1" -> 12, "v1.0.12" -> 12)
            val latestVersionCode = parseVersionCode(tagName, releaseTitle)

            Log.i(
                TAG,
                "GitHub Release parsed: tag='$tagName', title='$releaseTitle', latestVersionCode=$latestVersionCode, currentVersionCode=$currentVersionCode"
            )

            // Compare latest release's versionCode against BuildConfig.VERSION_CODE
            // Trigger update UI ONLY if latestVersionCode > currentVersionCode
            val hasNewVersion = if (latestVersionCode > 0) {
                latestVersionCode > currentVersionCode
            } else {
                isVersionNewer(
                    remoteVersion = tagName,
                    currentVersion = currentVersion,
                    currentVersionCode = currentVersionCode,
                    releaseTitle = releaseTitle
                )
            }

            if (!hasNewVersion) {
                Log.i(TAG, "No update available: latestVersionCode ($latestVersionCode) <= currentVersionCode ($currentVersionCode)")
                clearCachedUpdate(context)
                _isDialogVisible.value = false
                val noUpdate = UpdateResult.NoUpdate(currentVersion)
                _updateState.value = noUpdate
                return@withContext noUpdate
            }

            // Check if user previously ignored/skipped this version
            val skippedVersion = getSkippedVersion(context)
            if (!ignoreSkipped && skippedVersion.equals(tagName, ignoreCase = true)) {
                Log.i(TAG, "Version $tagName was previously skipped by the user.")
                _isDialogVisible.value = false
                val noUpdate = UpdateResult.NoUpdate(currentVersion)
                _updateState.value = noUpdate
                return@withContext noUpdate
            }

            // Update is strictly newer: prepare UpdateAvailable result
            val updateAvailable = UpdateResult.UpdateAvailable(
                latestVersion = tagName,
                latestVersionCode = latestVersionCode,
                releaseNotes = releaseNotes,
                downloadUrl = apkDownloadUrl,
                apkFileName = apkFileName,
                assetSize = assetSize,
                publishedAt = publishedAt
            )

            // Cache to SharedPreferences
            cacheUpdateAvailable(context, updateAvailable)

            _updateState.value = updateAvailable
            _isDialogVisible.value = true

            if (notifySystem) {
                UpdateNotificationManager.showUpdateAvailableNotification(
                    context = context,
                    latestVersion = tagName,
                    releaseNotes = releaseNotes
                )
            }

            return@withContext updateAvailable

        } catch (e: Exception) {
            Log.e(TAG, "Error checking for GitHub updates", e)
            val err = UpdateResult.Error("Failed to check for updates: ${e.localizedMessage ?: "Unknown network error"}", e)
            _updateState.value = err
            return@withContext err
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extracts numerical version code from tags such as:
     * - "debug-apk-build-12-1" -> 12
     * - "debug-apk-build-12" -> 12
     * - "v1.0.12" -> 12
     * - "1.0.12" -> 12
     * - "build-12" -> 12
     * - "12" -> 12
     * - Title "Order Master v1.0.12 (Build #12)" -> 12
     */
    fun parseVersionCode(tagName: String, releaseTitle: String? = null): Int {
        val cleanTag = tagName.trim()

        // 1. debug-apk-build-<run_number>-<attempt> or debug-apk-build-<run_number>
        val debugBuildMatch = Regex("""debug-apk-build-(\d+)""", RegexOption.IGNORE_CASE).find(cleanTag)
        if (debugBuildMatch != null) {
            val code = debugBuildMatch.groupValues[1].toIntOrNull()
            if (code != null && code > 0) return code
        }

        // 2. SemVer: 1.0.<run_number> or v1.0.<run_number>
        val semverMatch = Regex("""(?:v)?\d+\.\d+\.(\d+)""", RegexOption.IGNORE_CASE).find(cleanTag)
        if (semverMatch != null) {
            val code = semverMatch.groupValues[1].toIntOrNull()
            if (code != null && code > 0) return code
        }

        // 3. build-<number> or run-<number>
        val buildMatch = Regex("""(?:build|run)[-_](\d+)""", RegexOption.IGNORE_CASE).find(cleanTag)
        if (buildMatch != null) {
            val code = buildMatch.groupValues[1].toIntOrNull()
            if (code != null && code > 0) return code
        }

        // 4. Release title containing "Build #12" or "#12"
        if (!releaseTitle.isNullOrBlank()) {
            val titleMatch = Regex("""(?:build\s*#?|#)\s*(\d+)""", RegexOption.IGNORE_CASE).find(releaseTitle)
            if (titleMatch != null) {
                val code = titleMatch.groupValues[1].toIntOrNull()
                if (code != null && code > 0) return code
            }
        }

        // 5. Fallback: all numbers in tag
        val numbers = Regex("""\d+""").findAll(cleanTag).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isNotEmpty()) {
            return if (numbers.size >= 3) numbers.last() else numbers.first()
        }

        return 0
    }

    /**
     * Fallback SemVer comparison.
     */
    fun isVersionNewer(
        remoteVersion: String,
        currentVersion: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        releaseTitle: String? = null
    ): Boolean {
        val remoteCode = parseVersionCode(remoteVersion, releaseTitle)
        if (remoteCode > 0) {
            return remoteCode > currentVersionCode
        }

        val cleanRemote = cleanVersionString(remoteVersion)
        val cleanCurrent = cleanVersionString(currentVersion)
        if (cleanRemote.equals(cleanCurrent, ignoreCase = true)) {
            return false
        }

        val remoteNumbers = extractVersionNumbers(remoteVersion)
        val currentNumbers = extractVersionNumbers(currentVersion)

        if (remoteNumbers.isNotEmpty() && currentNumbers.isNotEmpty()) {
            val maxLen = maxOf(remoteNumbers.size, currentNumbers.size)
            for (i in 0 until maxLen) {
                val r = remoteNumbers.getOrElse(i) { 0 }
                val c = currentNumbers.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        }

        return cleanRemote != cleanCurrent && cleanRemote > cleanCurrent
    }

    fun extractVersionNumbers(versionStr: String): List<Int> {
        return Regex("""\d+""").findAll(versionStr).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    private fun cleanVersionString(version: String): String {
        return version.trim()
            .lowercase(Locale.ROOT)
            .removePrefix("v")
            .removePrefix("release-")
            .removePrefix("ver-")
            .trim()
    }

    fun showDialog() {
        if (_updateState.value is UpdateResult.UpdateAvailable) {
            _isDialogVisible.value = true
        }
    }

    fun dismissDialog() {
        _isDialogVisible.value = false
    }

    fun resetState() {
        _updateState.value = UpdateResult.Idle
        _isDialogVisible.value = false
    }

    fun skipVersion(context: Context, versionTag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIPPED_VERSION, versionTag)
            .apply()
        _isDialogVisible.value = false
        UpdateNotificationManager.clearAllUpdateNotifications(context)
    }

    fun ignoreVersion(context: Context, versionTag: String) {
        skipVersion(context, versionTag)
    }

    fun getSkippedVersion(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SKIPPED_VERSION, "") ?: ""
    }

    fun clearSkippedVersion(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SKIPPED_VERSION)
            .apply()
    }

    private fun saveLastCheckTime(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getLastCheckTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_TIME, 0L)
    }

    private fun cacheUpdateAvailable(context: Context, update: UpdateResult.UpdateAvailable) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_VERSION, update.latestVersion)
            .putInt(KEY_CACHED_VERSION_CODE, update.latestVersionCode)
            .putString(KEY_CACHED_RELEASE_NOTES, update.releaseNotes)
            .putString(KEY_CACHED_DOWNLOAD_URL, update.downloadUrl)
            .putString(KEY_CACHED_APK_FILENAME, update.apkFileName)
            .putLong(KEY_CACHED_ASSET_SIZE, update.assetSize)
            .apply()
    }

    private fun getCachedVersion(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_VERSION, "") ?: ""
    }

    private fun getCachedUpdateAvailable(context: Context): UpdateResult.UpdateAvailable? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_CACHED_VERSION, "") ?: ""
        val url = prefs.getString(KEY_CACHED_DOWNLOAD_URL, "") ?: ""
        if (version.isEmpty() || url.isEmpty()) return null
        return UpdateResult.UpdateAvailable(
            latestVersion = version,
            latestVersionCode = prefs.getInt(KEY_CACHED_VERSION_CODE, 0),
            releaseNotes = prefs.getString(KEY_CACHED_RELEASE_NOTES, "") ?: "",
            downloadUrl = url,
            apkFileName = prefs.getString(KEY_CACHED_APK_FILENAME, "app-debug.apk") ?: "app-debug.apk",
            assetSize = prefs.getLong(KEY_CACHED_ASSET_SIZE, 0L)
        )
    }

    private fun clearCachedUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CACHED_VERSION)
            .remove(KEY_CACHED_VERSION_CODE)
            .remove(KEY_CACHED_RELEASE_NOTES)
            .remove(KEY_CACHED_DOWNLOAD_URL)
            .remove(KEY_CACHED_APK_FILENAME)
            .remove(KEY_CACHED_ASSET_SIZE)
            .apply()
    }
}
