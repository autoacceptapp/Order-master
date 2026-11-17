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

    internal const val PREFS_NAME = "github_update_prefs"
    private const val KEY_AUTO_UPDATE_ENABLED = "key_auto_update_enabled"
    private const val KEY_SKIPPED_VERSION = "key_skipped_version"
    private const val KEY_LAST_CHECK_TIME = "key_last_check_time"
    private const val KEY_CACHED_VERSION = "key_cached_version"
    private const val KEY_CACHED_VERSION_CODE = "key_cached_version_code"
    private const val KEY_CACHED_RELEASE_NOTES = "key_cached_release_notes"
    private const val KEY_CACHED_DOWNLOAD_URL = "key_cached_download_url"
    internal const val KEY_CACHED_APK_FILENAME = "key_cached_apk_filename"
    private const val KEY_CACHED_ASSET_SIZE = "key_cached_asset_size"

    // 15 minutes throttle window to prevent aggressive GitHub API rate limiting
    const val CHECK_THROTTLE_WINDOW_MS = 15 * 60 * 1000L

    private val _updateState = MutableStateFlow<UpdateResult>(UpdateResult.Idle)
    val updateState: StateFlow<UpdateResult> = _updateState.asStateFlow()

    private val _isDialogVisible = MutableStateFlow(false)
    val isDialogVisible: StateFlow<Boolean> = _isDialogVisible.asStateFlow()

    fun isAutoUpdateEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPDATE_ENABLED, true)
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled)
            .apply()
    }

    /**
     * Automatic update checker called directly from MainActivity.onCreate().
     */
    fun autoCheckForUpdates(
        context: Context,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        force: Boolean = false
    ) {
        clearIfAlreadyUpdated(context)
        if (!GlobalAdminManager.isInAppUpdateActive.value) {
            Log.i(TAG, "In-App Updates disabled globally by Admin (isInAppUpdateActive=false); aborting update check.")
            return
        }
        if (!force && !isAutoUpdateEnabled(context)) {
            return
        }
        scope.launch {
            if (!GlobalAdminManager.isInAppUpdateActive.value) {
                Log.i(TAG, "In-App Updates disabled globally by Admin; aborting coroutine update check.")
                return@launch
            }
            val lastCheck = getLastCheckTime(context)
            val now = System.currentTimeMillis()
            val timeSinceLastCheck = now - lastCheck

            val skippedVersion = getSkippedVersion(context)
            val cachedVersion = getCachedVersion(context)

            // If checked recently and not forced, check if cached update is still relevant
            if (!force && timeSinceLastCheck < CHECK_THROTTLE_WINDOW_MS && timeSinceLastCheck >= 0) {
                if (cachedVersion.isNotEmpty() && !cachedVersion.equals(skippedVersion, ignoreCase = true)) {
                    val cachedInfo = getCachedUpdateAvailable(context)
                    if (cachedInfo != null &&
                        (cachedInfo.latestVersionCode <= 0 || BuildConfig.VERSION_CODE < cachedInfo.latestVersionCode) &&
                        isVersionNewer(
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
                    } else if (cachedInfo != null) {
                        clearCachedUpdate(context)
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
        clearIfAlreadyUpdated(context, currentVersionCode, currentVersion)
        if (!GlobalAdminManager.isInAppUpdateActive.value) {
            Log.i(TAG, "In-App Updates disabled globally by Admin (isInAppUpdateActive=false); aborting checkForUpdates.")
            _updateState.value = UpdateResult.Idle
            return@withContext UpdateResult.Idle
        }
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

            // Inspect assets array to find a build-type compatible compiled APK asset (.apk)
            val assetsArray: JSONArray? = json.optJSONArray("assets")
            val preferredApkName = resolveFallbackApkFileName()
            val selectedAsset = selectCompatibleApkAsset(assetsArray)
            var apkDownloadUrl: String? = selectedAsset?.downloadUrl
            var apkFileName = selectedAsset?.fileName ?: preferredApkName
            var assetSize = selectedAsset?.size ?: 0L

            // Fallback ONLY to compatible dynamicAppDownloadUrl; never fall back to a mismatched build-type APK
            if (apkDownloadUrl.isNullOrEmpty()) {
                val dynamicConfigUrl = com.example.data.repository.AppConfigRepository.currentAppConfig.value.dynamicAppDownloadUrl.trim()
                val dynamicFileName = dynamicConfigUrl.substringAfterLast('/').substringBefore('?').trim()
                if (dynamicConfigUrl.isNotEmpty() &&
                    ApkDownloader.isCompatibleApkForBuildType(dynamicConfigUrl, dynamicFileName)
                ) {
                    apkDownloadUrl = dynamicConfigUrl
                    apkFileName = dynamicFileName.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: preferredApkName
                } else {
                    Log.w(
                        TAG,
                        "Release '$tagName' has no compatible ${if (BuildConfig.DEBUG) "debug" else "release"} APK asset and dynamicAppDownloadUrl is empty/incompatible; returning NoUpdate."
                    )
                    saveLastCheckTime(context)
                    clearCachedUpdate(context)
                    _isDialogVisible.value = false
                    val noUpdate = UpdateResult.NoUpdate(currentVersion)
                    _updateState.value = noUpdate
                    return@withContext noUpdate
                }
            }

            // Save last checked timestamp
            saveLastCheckTime(context)

            // Extract numeric version code from tag name or title (e.g. "v1.0.2-build-105" -> 105)
            val latestVersionCode = parseVersionCode(tagName, releaseTitle)

            Log.i(
                TAG,
                "GitHub Release parsed: tag='$tagName', title='$releaseTitle', latestVersionCode=$latestVersionCode, currentVersionCode=$currentVersionCode"
            )

            // Compare latest release against current installed version:
            // Prioritize explicit build/run versionCode when present; fall back to SemVer when equal or absent.
            val hasExplicitBuild = hasExplicitBuildNumber(tagName, releaseTitle)
            val hasNewVersion = if (hasExplicitBuild && latestVersionCode > 0) {
                when {
                    latestVersionCode > currentVersionCode -> true
                    latestVersionCode < currentVersionCode -> false
                    else -> isVersionNewer(
                        remoteVersion = tagName,
                        currentVersion = currentVersion,
                        currentVersionCode = currentVersionCode,
                        releaseTitle = releaseTitle
                    )
                }
            } else {
                val isSemVerNewer = VersionUtils.isUpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = tagName
                )
                val isCodeNewer = latestVersionCode > 0 && latestVersionCode > currentVersionCode
                isSemVerNewer || isCodeNewer || isVersionNewer(
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

    internal data class SelectedApkAsset(
        val downloadUrl: String,
        val fileName: String,
        val size: Long
    )

    internal fun selectCompatibleApkAsset(
        assetsArray: JSONArray?,
        isDebugBuild: Boolean = BuildConfig.DEBUG
    ): SelectedApkAsset? {
        if (assetsArray == null || assetsArray.length() == 0) return null
        val preferredExact = resolveFallbackApkFileName(isDebugBuild)
        var exactMatch: SelectedApkAsset? = null
        var typedMatch: SelectedApkAsset? = null
        var genericMatch: SelectedApkAsset? = null

        for (i in 0 until assetsArray.length()) {
            val assetObj = assetsArray.optJSONObject(i) ?: continue
            val name = assetObj.optString("name", "").trim()
            val downloadUrl = assetObj.optString("browser_download_url", "").trim()
            if (!name.endsWith(".apk", ignoreCase = true) || downloadUrl.isEmpty()) continue

            val isDebugAsset = name.contains("debug", ignoreCase = true)
            val isReleaseAsset = name.contains("release", ignoreCase = true)

            if (!isDebugBuild) {
                // Release build: NEVER select debug APKs
                if (isDebugAsset) continue
                if (name.equals(preferredExact, ignoreCase = true)) {
                    exactMatch = SelectedApkAsset(downloadUrl, name, assetObj.optLong("size", 0L))
                    break
                } else if (name.endsWith("-release.apk", ignoreCase = true) || isReleaseAsset) {
                    if (typedMatch == null) {
                        typedMatch = SelectedApkAsset(downloadUrl, name, assetObj.optLong("size", 0L))
                    }
                } else if (genericMatch == null) {
                    genericMatch = SelectedApkAsset(downloadUrl, name, assetObj.optLong("size", 0L))
                }
            } else {
                // Debug build: prefer app-debug.apk / *-debug.apk; NEVER select release APKs
                if (isReleaseAsset) continue
                if (name.equals(preferredExact, ignoreCase = true)) {
                    exactMatch = SelectedApkAsset(downloadUrl, name, assetObj.optLong("size", 0L))
                    break
                } else if (name.endsWith("-debug.apk", ignoreCase = true) || isDebugAsset) {
                    if (typedMatch == null) {
                        typedMatch = SelectedApkAsset(downloadUrl, name, assetObj.optLong("size", 0L))
                    }
                } else if (genericMatch == null) {
                    genericMatch = SelectedApkAsset(downloadUrl, name, assetObj.optLong("size", 0L))
                }
            }
        }

        return exactMatch ?: typedMatch ?: genericMatch
    }

    internal fun hasExplicitBuildNumber(tagName: String, releaseTitle: String? = null): Boolean {
        val cleanTag = tagName.trim()
        if (Regex("""debug-apk-build-(\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(cleanTag)) return true
        if (Regex("""(?:build|run)[-_](\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(cleanTag)) return true
        if (!releaseTitle.isNullOrBlank() &&
            Regex("""(?:build\s*#?|#)\s*(\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(releaseTitle)
        ) {
            return true
        }
        return false
    }

    /**
     * Extracts numerical version code from tags such as:
     * - "v1.0.2-build-105" -> 105
     * - "v1.0.2-debug-build-105" -> 105
     * - "debug-apk-build-12-1" -> 12
     * - "debug-apk-build-12" -> 12
     * - Title "Order Master v1.0.2-build-105 (Build #105)" -> 105
     * - "v1.0.12" -> 12
     * - "1.0.12" -> 12
     * - "build-12" -> 12
     * - "12" -> 12
     */
    fun parseVersionCode(tagName: String, releaseTitle: String? = null): Int {
        val cleanTag = tagName.trim()

        // 1. debug-apk-build-<run_number>-<attempt> or debug-apk-build-<run_number>
        val debugBuildMatch = Regex("""debug-apk-build-(\d+)""", RegexOption.IGNORE_CASE).find(cleanTag)
        if (debugBuildMatch != null) {
            val code = debugBuildMatch.groupValues[1].toIntOrNull()
            if (code != null && code > 0) return code
        }

        // 2. Explicit build-<number> or run-<number> BEFORE SemVer patch extraction
        val buildMatch = Regex("""(?:build|run)[-_](\d+)""", RegexOption.IGNORE_CASE).find(cleanTag)
        if (buildMatch != null) {
            val code = buildMatch.groupValues[1].toIntOrNull()
            if (code != null && code > 0) return code
        }

        // 3. Release title containing "Build #105" or "#105"
        if (!releaseTitle.isNullOrBlank()) {
            val titleMatch = Regex("""(?:build\s*#?|#)\s*(\d+)""", RegexOption.IGNORE_CASE).find(releaseTitle)
            if (titleMatch != null) {
                val code = titleMatch.groupValues[1].toIntOrNull()
                if (code != null && code > 0) return code
            }
        }

        // 4. SemVer fallback: 1.0.<patch> or v1.0.<patch>
        val semverMatch = Regex("""(?:v)?\d+\.\d+\.(\d+)""", RegexOption.IGNORE_CASE).find(cleanTag)
        if (semverMatch != null) {
            val code = semverMatch.groupValues[1].toIntOrNull()
            if (code != null && code > 0) return code
        }

        // 5. Fallback: all numbers in tag
        val numbers = Regex("""\d+""").findAll(cleanTag).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isNotEmpty()) {
            return if (numbers.size >= 3) numbers.last() else numbers.first()
        }

        return 0
    }

    /**
     * Compares remote release version against current installed version/build.
     * Prioritizes explicit build/run versionCode when present; falls back to SemVer when equal or absent.
     */
    fun isVersionNewer(
        remoteVersion: String,
        currentVersion: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        releaseTitle: String? = null
    ): Boolean {
        val hasExplicitBuild = hasExplicitBuildNumber(remoteVersion, releaseTitle)
        if (hasExplicitBuild) {
            val remoteCode = parseVersionCode(remoteVersion, releaseTitle)
            if (remoteCode > 0) {
                if (remoteCode > currentVersionCode) return true
                if (remoteCode < currentVersionCode) return false
                // Equal versionCode: fall back to SemVer only when remoteVersion parses as valid SemVer
                if (VersionUtils.parseSemVer(remoteVersion) != null &&
                    VersionUtils.parseSemVer(currentVersion) != null
                ) {
                    return VersionUtils.isUpdateAvailable(currentVersion, remoteVersion)
                }
                return false
            }
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
            return remoteNumbers.size > currentNumbers.size
        }

        return remoteNumbers.size > currentNumbers.size
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
        if (!GlobalAdminManager.isInAppUpdateActive.value) return
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

    internal fun cacheUpdateAvailable(context: Context, update: UpdateResult.UpdateAvailable) {
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

    /**
     * Clears cached update metadata immediately if the installed app has already caught up to
     * or surpassed the cached release version.
     */
    fun clearIfAlreadyUpdated(
        context: Context,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedVersion = prefs.getString(KEY_CACHED_VERSION, "") ?: ""
        val cachedVersionCode = prefs.getInt(KEY_CACHED_VERSION_CODE, 0)
        if (cachedVersion.isEmpty() && cachedVersionCode <= 0) return false

        val isAlreadyUpToDate = (cachedVersionCode > 0 && currentVersionCode >= cachedVersionCode) ||
            (cachedVersionCode <= 0 && cachedVersion.isNotEmpty() && !isVersionNewer(
                remoteVersion = cachedVersion,
                currentVersion = currentVersion,
                currentVersionCode = currentVersionCode
            ))

        if (isAlreadyUpToDate) {
            Log.i(
                TAG,
                "Clearing stale cached update ($cachedVersion / code $cachedVersionCode); installed build $currentVersion ($currentVersionCode) is already up to date."
            )
            clearCachedUpdate(context)
            return true
        }
        return false
    }

    internal fun getCachedUpdateAvailable(
        context: Context,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        currentVersion: String = BuildConfig.VERSION_NAME,
        isDebugBuild: Boolean = BuildConfig.DEBUG
    ): UpdateResult.UpdateAvailable? {
        if (clearIfAlreadyUpdated(context, currentVersionCode, currentVersion)) {
            return null
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_CACHED_VERSION, "") ?: ""
        val url = prefs.getString(KEY_CACHED_DOWNLOAD_URL, "") ?: ""
        if (version.isEmpty() || url.isEmpty()) return null
        val defaultApkName = resolveFallbackApkFileName(isDebugBuild)
        val apkFileName = prefs.getString(KEY_CACHED_APK_FILENAME, defaultApkName) ?: defaultApkName
        if (!isDebugBuild && !ApkDownloader.isCompatibleApkForBuildType(url, apkFileName, isDebugBuild = false)) {
            Log.w(TAG, "Purging cached incompatible debug APK update ($apkFileName / $url) on release build.")
            clearCachedUpdate(context)
            return null
        }
        return UpdateResult.UpdateAvailable(
            latestVersion = version,
            latestVersionCode = prefs.getInt(KEY_CACHED_VERSION_CODE, 0),
            releaseNotes = prefs.getString(KEY_CACHED_RELEASE_NOTES, "") ?: "",
            downloadUrl = url,
            apkFileName = apkFileName,
            assetSize = prefs.getLong(KEY_CACHED_ASSET_SIZE, 0L)
        )
    }

    internal fun resolveFallbackApkFileName(isDebugBuild: Boolean = BuildConfig.DEBUG): String {
        return if (isDebugBuild) "app-debug.apk" else "app-release.apk"
    }

    internal fun resolveFallbackDownloadUrl(
        tagName: String,
        isDebugBuild: Boolean = BuildConfig.DEBUG
    ): String {
        val fileName = resolveFallbackApkFileName(isDebugBuild)
        return "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/$fileName"
    }

    fun clearCachedUpdate(context: Context) {
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
