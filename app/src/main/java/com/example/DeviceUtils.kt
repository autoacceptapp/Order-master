package com.example

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * DeviceUtils
 *
 * Implements hardware-locked device fingerprinting for non-resettable licensing.
 * Combines Android's Secure Android ID with immutable hardware properties and Widevine Crypto Hardware UUID.
 *
 * Guarantees:
 * - Deterministic output: Same physical device will always produce the exact same Hardware ID.
 * - Non-bypassable: Clearing app data, uninstalling, or changing Google accounts will NOT change this ID.
 */
object DeviceUtils {

    private const val TAG = "DeviceUtils"
    private const val PREFS_NAME = "device_security_prefs"
    private const val KEY_CACHED_HW_ID = "cached_device_hw_id"
    private const val KEY_CUSTOM_USER_ID = "custom_user_id"

    // Widevine DRM Scheme UUID (Standard Android hardware enclave)
    private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    @Volatile
    private var cachedHardwareId: String? = null

    @Volatile
    private var cachedCustomUserId: String? = null

    /**
     * Returns a persistent custom User ID formatted as "OM-USER-XXXXXXXX" (8 uppercase alphanumeric characters).
     * Saved in SharedPreferences so it remains permanent for the app installation.
     */
    @Synchronized
    fun getCustomUserId(context: Context): String {
        cachedCustomUserId?.let { return it }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_CUSTOM_USER_ID, null)
        if (!savedId.isNullOrBlank()) {
            cachedCustomUserId = savedId
            return savedId
        }

        // Generate 8-character random uppercase alphanumeric string
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val randomSuffix = (1..8)
            .map { allowedChars.random() }
            .joinToString("")
        val newUserId = "OM-USER-$randomSuffix"

        prefs.edit().putString(KEY_CUSTOM_USER_ID, newUserId).apply()
        cachedCustomUserId = newUserId
        Log.i(TAG, "Generated and stored permanent Custom User ID: $newUserId")
        return newUserId
    }

    /**
     * Returns an immutable, hardware-locked Device Hardware Fingerprint.
     * Example format: "HW-A1B2C3D4E5F6"
     */
    @Synchronized
    fun getDeviceHardwareId(context: Context): String {
        cachedHardwareId?.let { return it }

        // Check local cache first
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_CACHED_HW_ID, null)
        if (!savedId.isNullOrBlank()) {
            cachedHardwareId = savedId
            return savedId
        }

        // Build hardware fingerprint combining multiple physical device characteristics
        val rawHardwareString = buildRawHardwareString(context)
        val sha256Hex = sha256(rawHardwareString)

        // Format as standardized hardware key: "HW-" followed by first 16 characters in groups
        val formattedId = "HW-" + sha256Hex.take(16).uppercase(Locale.US)

        prefs.edit().putString(KEY_CACHED_HW_ID, formattedId).apply()
        cachedHardwareId = formattedId
        Log.i(TAG, "Generated Immutable Device Hardware ID: $formattedId")
        return formattedId
    }

    /**
     * Builds a comprehensive hardware fingerprint string from immutable hardware attributes.
     */
    private fun buildRawHardwareString(context: Context): String {
        val sb = StringBuilder()

        // 1. Android Secure ID
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_AID"
        } catch (e: Exception) {
            "AID_ERROR"
        }
        sb.append("AID:").append(androidId).append("|")

        // 2. Physical Hardware & Board Properties
        sb.append("BOARD:").append(Build.BOARD).append("|")
        sb.append("BRAND:").append(Build.BRAND).append("|")
        sb.append("HARDWARE:").append(Build.HARDWARE).append("|")
        sb.append("MODEL:").append(Build.MODEL).append("|")
        sb.append("MANUFACTURER:").append(Build.MANUFACTURER).append("|")
        sb.append("DEVICE:").append(Build.DEVICE).append("|")
        sb.append("PRODUCT:").append(Build.PRODUCT).append("|")

        // 3. MediaDrm Widevine Hardware Cryptographic Module ID (if supported)
        val widevineId = getWidevineUniqueId()
        if (widevineId != null) {
            sb.append("WIDEVINE:").append(widevineId).append("|")
        }

        return sb.toString()
    }

    // Known root binaries and su paths
    private val KNOWN_SU_PATHS = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/system/xbin/daemonsu"
    )

    // Known root management and hooking packages
    private val KNOWN_ROOT_PACKAGES = arrayOf(
        "com.noshufou.android.su",
        "com.thirdparty.superuser",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.topjohnwu.magisk",
        "de.robv.android.xposed.installer",
        "com.saurik.substrate",
        "com.chelpus.lackypatch"
    )

    /**
     * Detects if the device is running inside an emulator.
     */
    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("vbox86p")
                || Build.BOARD.lowercase(Locale.ROOT).contains("goldfish"))
    }

    /**
     * Detects if the device environment is rooted or running hooking frameworks.
     */
    fun isRooted(context: Context? = null): Boolean {
        if (isUnitTestEnvironment()) return false

        // 1. Check test-keys
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }

        // 2. Check known su binaries
        for (path in KNOWN_SU_PATHS) {
            try {
                if (java.io.File(path).exists()) return true
            } catch (_: Throwable) {}
        }

        // 3. Check Xposed / Substrate / Frida hooking classes
        try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            return true
        } catch (_: Throwable) {}

        // 4. Check root management packages if context is provided
        if (context != null) {
            val pm = context.packageManager
            for (pkg in KNOWN_ROOT_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    return true
                } catch (_: Throwable) {}
            }
        }

        return false
    }

    /**
     * Detects hardware spoofing (spoofed Android ID, missing Widevine enclave on physical device).
     */
    fun isHardwareSpoofed(context: Context): Boolean {
        if (isUnitTestEnvironment()) return false

        // 1. Check for dummy or corrupted ANDROID_ID
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) { null }

        if (androidId.isNullOrBlank() ||
            androidId.equals("9774d56d682e549c", ignoreCase = true) || // Known default dummy ID
            androidId.equals("0000000000000000") ||
            androidId.length < 8
        ) {
            Log.w(TAG, "Hardware spoofing alert: invalid or dummy ANDROID_ID ($androidId)")
            return true
        }

        // 2. Check for missing Widevine DRM on physical Android devices (API 24+)
        if (!isEmulator()) {
            val widevineSupported = try {
                MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)
            } catch (_: Throwable) { false }

            if (!widevineSupported) {
                Log.w(TAG, "Hardware spoofing alert: Widevine DRM unsupported on physical device build.")
                return true
            }
        }

        return false
    }

    /**
     * Checks if SafetyNet / Play Integrity / App Check signals indicate an untrusted environment.
     */
    fun isPlayIntegrityOrSafetyNetCompromised(context: Context): Boolean {
        if (isUnitTestEnvironment()) return false

        val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable && (Build.TAGS?.contains("test-keys") == true || Build.TYPE == "userdebug")) {
            Log.w(TAG, "Play Integrity alert: release app running on test-keys/userdebug image")
            return true
        }

        return false
    }

    /**
     * Evaluates comprehensive integrity signals (Play Integrity, Root, Emulator, Hardware Spoofing).
     */
    fun isDeviceCompromised(context: Context): Boolean {
        if (isUnitTestEnvironment()) return false
        return isEmulator() || isRooted(context) || isHardwareSpoofed(context) || isPlayIntegrityOrSafetyNetCompromised(context)
    }

    /**
     * Enforces that the Free Trial is ONLY granted to legitimate, uncompromised physical hardware.
     * Blocks trial entirely if rooting or hardware spoofing is detected.
     */
    fun isFreeTrialAllowed(context: Context): Boolean {
        if (isUnitTestEnvironment()) return true
        if (isDeviceCompromised(context)) {
            Log.w(TAG, "Free trial denied: Device is rooted, emulated, or hardware-spoofed.")
            return false
        }
        return true
    }

    private fun isUnitTestEnvironment(): Boolean {
        return Build.FINGERPRINT.contains("robolectric", ignoreCase = true) ||
                runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess
    }

    /**
     * Extracts Widevine hardware crypto module ID if present on device.
     * Bypasses on emulators to prevent DrmHalHidl passthrough failure errors.
     */
    private fun getWidevineUniqueId(): String? {
        if (isEmulator()) {
            return null
        }
        return try {
            if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) {
                return null
            }
            val drm = MediaDrm(WIDEVINE_UUID)
            val bytes = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                drm.close()
            } else {
                @Suppress("DEPRECATION")
                drm.release()
            }
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Computes SHA-256 hash.
     */
    fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback hash
            input.hashCode().toString(16)
        }
    }

    /**
     * Retrieves readable device hardware details for diagnostics and store display.
     */
    fun getHardwareSpecs(): Map<String, String> {
        return mapOf(
            "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Board" to Build.BOARD,
            "Hardware" to Build.HARDWARE,
            "Android Version" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Fingerprint" to Build.FINGERPRINT,
            "IsEmulator" to isEmulator().toString()
        )
    }
}
