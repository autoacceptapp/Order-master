package com.example

import android.content.Context
import android.content.SharedPreferences
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
        val releaseNotes: String,
        val downloadUrl: String,
        val apkFileName: String,
        val assetSize: Long = 0L,
        val publishedAt: String = ""
    ) : UpdateResult()
    data class NoUpdate(val currentVersion: String) : UpdateResult()
    data class Error(val message: String, val throwable: Throwable? = null) : UpdateResult()
}

/**
 * Production-ready utility to check for new releases directly from the GitHub Releases API.
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
     * Automatic update checker called directly from MainActivity.onCreate() or ViewModel.init.
     * Throttles network calls via SharedPreferences timestamp to protect against API rate-limits.
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
                    if (cachedInfo != null && isVersionNewer(cachedInfo.latestVersion, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
                        Log.i(TAG, "Restoring cached update from SharedPreferences: ${cachedInfo.latestVersion}")
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
                setRequestProperty("User-Agent", "AutoAccept-Android-Updater/${currentVersion}")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                val noUpdate = UpdateResult.NoUpdate(currentVersion)
                _updateState.value = noUpdate
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
            val releaseNotes = json.optString("body", "Bug fixes and performance improvements.").trim()
            val publishedAt = json.optString("published_at", "")

            if (tagName.isEmpty()) {
                val err = UpdateResult.Error("Invalid GitHub release data: tag_name is missing")
                _updateState.value = err
                return@withContext err
            }

            // Inspect assets array to find the compiled APK asset (.apk)
            val assetsArray: JSONArray? = json.optJSONArray("assets")
            var apkDownloadUrl: String? = null
            var apkFileName = "OrderMaster_update.apk"
            var assetSize = 0L

            if (assetsArray != null && assetsArray.length() > 0) {
                for (i in 0 until assetsArray.length()) {
                    val assetObj = assetsArray.getJSONObject(i)
                    val name = assetObj.optString("name", "")
                    val downloadUrl = assetObj.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                        apkDownloadUrl = downloadUrl
                        apkFileName = name
                        assetSize = assetObj.optLong("size", 0L)
                        break
                    }
                }
            }

            // If no APK asset explicitly attached, check fallback browser download
            if (apkDownloadUrl.isNullOrEmpty()) {
                val htmlUrl = json.optString("html_url", "")
                Log.w(TAG, "No direct .apk asset attached in release $tagName. HTML: $htmlUrl")
                val err = UpdateResult.Error("Release $tagName does not contain a compiled APK asset (.apk).")
                _updateState.value = err
                return@withContext err
            }

            // Save last checked timestamp
            saveLastCheckTime(context)

            // Check if user previously ignored/skipped this version
            val skippedVersion = getSkippedVersion(context)
            if (!ignoreSkipped && skippedVersion.equals(tagName, ignoreCase = true)) {
                Log.i(TAG, "Version $tagName was previously skipped by the user.")
                val noUpdate = UpdateResult.NoUpdate(currentVersion)
                _updateState.value = noUpdate
                return@withContext noUpdate
            }

            val hasNewVersion = isVersionNewer(
                remoteVersion = tagName,
                currentVersion = currentVersion,
                currentVersionCode = currentVersionCode
            )

            if (hasNewVersion) {
                val updateAvailable = UpdateResult.UpdateAvailable(
                    latestVersion = tagName,
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
            } else {
                clearCachedUpdate(context)
                val noUpdate = UpdateResult.NoUpdate(currentVersion)
                _updateState.value = noUpdate
                return@withContext noUpdate
            }

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
     * Shows the in-app update dialog (e.g. triggered via notification or manual click).
     */
    fun showDialog() {
        if (_updateState.value is UpdateResult.UpdateAvailable) {
            _isDialogVisible.value = true
        }
    }

    /**
     * Dismisses the dialog temporarily ("Later"). Does not mark the version as permanently skipped.
     */
    fun dismissDialog() {
        _isDialogVisible.value = false
    }

    /**
     * Resets the update state to Idle.
     */
    fun resetState() {
        _updateState.value = UpdateResult.Idle
        _isDialogVisible.value = false
    }

    /**
     * Saves the skipped/ignored version tag in SharedPreferences and clears active dialog.
     */
    fun skipVersion(context: Context, versionTag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIPPED_VERSION, versionTag)
            .apply()
        _isDialogVisible.value = false
        UpdateNotificationManager.clearAllUpdateNotifications(context)
    }

    /**
     * Alias for skipVersion ("Ignore" action).
     */
    fun ignoreVersion(context: Context, versionTag: String) {
        skipVersion(context, versionTag)
    }

    /**
     * Retrieves the previously skipped version.
     */
    fun getSkippedVersion(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SKIPPED_VERSION, "") ?: ""
    }

    /**
     * Clears any skipped version preference.
     */
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
            releaseNotes = prefs.getString(KEY_CACHED_RELEASE_NOTES, "") ?: "",
            downloadUrl = url,
            apkFileName = prefs.getString(KEY_CACHED_APK_FILENAME, "OrderMaster_update.apk") ?: "OrderMaster_update.apk",
            assetSize = prefs.getLong(KEY_CACHED_ASSET_SIZE, 0L)
        )
    }

    private fun clearCachedUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CACHED_VERSION)
            .remove(KEY_CACHED_RELEASE_NOTES)
            .remove(KEY_CACHED_DOWNLOAD_URL)
            .remove(KEY_CACHED_APK_FILENAME)
            .remove(KEY_CACHED_ASSET_SIZE)
            .apply()
    }

    /**
     * Extracts numerical components from version strings such as:
     * - "v1.2.0" -> [1, 2, 0]
     * - "debug-apk-build-7-1" -> [7, 1]
     * - "release-build-12" -> [12]
     * - "2.0" -> [2, 0]
     */
    fun extractVersionNumbers(versionStr: String): List<Int> {
        val matches = Regex("\\d+").findAll(versionStr).mapNotNull { it.value.toIntOrNull() }.toList()
        return matches
    }

    /**
     * SemVer and Build-Number aware comparison between remote version string
     * (e.g. "debug-apk-build-7-1", "v1.2.0", "build-8") and the locally installed version.
     *
     * @return true if remoteVersion is strictly newer than currentVersion.
     */
    fun isVersionNewer(
        remoteVersion: String,
        currentVersion: String,
        currentVersionCode: Int = BuildConfig.VERSION_CODE
    ): Boolean {
        val cleanRemote = cleanVersionString(remoteVersion)
        val cleanCurrent = cleanVersionString(currentVersion)

        // Exact match -> not newer
        if (cleanRemote.equals(cleanCurrent, ignoreCase = true)) {
            return false
        }

        val remoteNumbers = extractVersionNumbers(remoteVersion)
        val currentNumbers = extractVersionNumbers(currentVersion)

        if (remoteNumbers.isNotEmpty()) {
            if (currentNumbers.isNotEmpty()) {
                val maxLen = maxOf(remoteNumbers.size, currentNumbers.size)
                for (i in 0 until maxLen) {
                    val r = remoteNumbers.getOrElse(i) { 0 }
                    val c = currentNumbers.getOrElse(i) { 0 }
                    if (r > c) return true
                    if (r < c) return false
                }
            } else {
                // If current has no extractable numbers, compare against versionCode
                if (remoteNumbers.first() > currentVersionCode) {
                    return true
                }
            }
        }

        // Fallback: If numeric parts couldn't distinguish, fallback to lexicographical comparison
        return cleanRemote != cleanCurrent && cleanRemote > cleanCurrent
    }

    private fun cleanVersionString(version: String): String {
        return version.trim()
            .lowercase(Locale.ROOT)
            .removePrefix("v")
            .removePrefix("release-")
            .removePrefix("ver-")
            .trim()
    }
}
