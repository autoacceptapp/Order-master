package com.example

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _updateState = MutableStateFlow<UpdateResult>(UpdateResult.Idle)
    val updateState: StateFlow<UpdateResult> = _updateState.asStateFlow()

    /**
     * Checks for updates against the GitHub Releases API asynchronously.
     *
     * @param context Application context for reading SharedPreferences
     * @param currentVersion Current app version name (e.g., BuildConfig.VERSION_NAME or "1.0")
     * @param ignoreSkipped If true, checks and notifies even if the user previously selected "Skip this version"
     * @return UpdateResult describing whether an update is available, not available, or failed
     */
    suspend fun checkForUpdates(
        context: Context,
        currentVersion: String = BuildConfig.VERSION_NAME,
        ignoreSkipped: Boolean = false
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
            val releaseNotes = json.optString("body", "No changelog provided.").trim()
            val publishedAt = json.optString("published_at", "")

            if (tagName.isEmpty()) {
                val err = UpdateResult.Error("Invalid GitHub release data: tag_name is missing")
                _updateState.value = err
                return@withContext err
            }

            // Inspect assets array to find the APK asset
            val assetsArray: JSONArray? = json.optJSONArray("assets")
            var apkDownloadUrl: String? = null
            var apkFileName = "update.apk"
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

            // If no APK asset in assets array, check if assets contains any valid browser download or fallback to zipball
            if (apkDownloadUrl.isNullOrEmpty()) {
                // Check html_url or direct download fallback
                val htmlUrl = json.optString("html_url", "")
                Log.w(TAG, "No direct .apk asset attached in release $tagName. HTML: $htmlUrl")
                val err = UpdateResult.Error("Release $tagName does not contain a compiled APK asset (.apk).")
                _updateState.value = err
                return@withContext err
            }

            // Save last checked timestamp
            saveLastCheckTime(context)

            // Check if user previously chose to skip this specific version
            val skippedVersion = getSkippedVersion(context)
            if (!ignoreSkipped && skippedVersion.equals(tagName, ignoreCase = true)) {
                Log.i(TAG, "Version $tagName was previously skipped by the user.")
                val noUpdate = UpdateResult.NoUpdate(currentVersion)
                _updateState.value = noUpdate
                return@withContext noUpdate
            }

            val hasNewVersion = isVersionNewer(remoteVersion = tagName, currentVersion = currentVersion)
            if (hasNewVersion) {
                val updateAvailable = UpdateResult.UpdateAvailable(
                    latestVersion = tagName,
                    releaseNotes = releaseNotes,
                    downloadUrl = apkDownloadUrl,
                    apkFileName = apkFileName,
                    assetSize = assetSize,
                    publishedAt = publishedAt
                )
                _updateState.value = updateAvailable
                return@withContext updateAvailable
            } else {
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
     * Resets the update state to Idle (e.g., when the user closes the dialog or defers).
     */
    fun resetState() {
        _updateState.value = UpdateResult.Idle
    }

    /**
     * Saves the skipped version tag in SharedPreferences so the user is not nagged repeatedly.
     */
    fun skipVersion(context: Context, versionTag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIPPED_VERSION, versionTag)
            .apply()
        resetState()
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

    /**
     * SemVer-aware comparison between remote version string (e.g. "v1.2.0" or "2.0")
     * and the locally installed app version (e.g. "1.0" or "v1.0.0").
     *
     * @return true if remoteVersion is strictly newer than currentVersion.
     */
    fun isVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
        val cleanRemote = cleanVersionString(remoteVersion)
        val cleanCurrent = cleanVersionString(currentVersion)

        // Fast path: exact string match
        if (cleanRemote == cleanCurrent) {
            return false
        }

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }

        // If numeric parts are equal, fall back to string comparison (e.g. beta/alpha)
        return cleanRemote > cleanCurrent
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
