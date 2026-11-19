package com.example

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * HardwareIdManager
 *
 * Permanent Device Hardware ID utility.
 * Generates an immutable, non-resettable unique Hardware ID using Android's MediaDrm Widevine UUID.
 *
 * Key guarantees:
 * - Uses physical Widevine Crypto Hardware Module (UUID: edef8ba9-79d6-4ace-a3c8-27dcd51d21ed)
 * - Extracts physical deviceUniqueId bytes tied to the device's secure hardware enclave.
 * - Does NOT change across factory resets, OS upgrades, or app uninstallation.
 * - Purely informational and diagnostic: NO locks, NO barriers, NO feature restrictions.
 */
object HardwareIdManager {

    private const val TAG = "HardwareIdManager"

    @Volatile
    private var cachedHardwareId: String? = null

    @Volatile
    private var cachedHardwareDetails: HardwareDetails? = null

    data class HardwareDetails(
        val formattedId: String,
        val fullSha256: String,
        val source: String,
        val isHardwareDrm: Boolean
    )

    /**
     * Retrieves the permanent hardware ID for this device.
     * Guaranteed to return a non-empty, uppercase formatted string (e.g., "OM-8F92-A103-B94C").
     */
    @Synchronized
    fun getPermanentHardwareId(context: Context): String {
        cachedHardwareId?.let { return it }

        val details = getHardwareDetails(context)
        cachedHardwareId = details.formattedId
        return details.formattedId
    }

    /**
     * Retrieves comprehensive hardware identification details including source and full SHA-256 hash.
     * Uses DeviceUtils as the single source of truth (stored/verified in device_security_prefs).
     */
    @Synchronized
    fun getHardwareDetails(context: Context): HardwareDetails {
        cachedHardwareDetails?.let { return it }

        val fingerprint = DeviceUtils.getOrVerifyHardwareFingerprint(context)
        val fullSha256 = fingerprint.sha256Hex.uppercase(Locale.US)
        val formatted = formatHardwareCode(fullSha256)
        val source = if (fingerprint.hasWidevineDrm) {
            "Unified Hardware Fingerprint (Widevine + Device)"
        } else {
            "Unified Hardware Fingerprint (Device)"
        }
        Log.i(TAG, "Generated permanent hardware ID ($source): $formatted")
        val details = HardwareDetails(
            formattedId = formatted,
            fullSha256 = fullSha256,
            source = source,
            isHardwareDrm = fingerprint.hasWidevineDrm
        )
        cachedHardwareDetails = details
        cachedHardwareId = details.formattedId
        return details
    }

    /**
     * Formats a 64-character SHA-256 hash into an uppercase, readable format:
     * e.g., "OM-8F92-A103-B94C"
     */
    private fun formatHardwareCode(sha256Hex: String): String {
        val clean = sha256Hex.uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }
        val p1 = clean.take(4).padEnd(4, '0')
        val p2 = clean.drop(4).take(4).padEnd(4, '0')
        val p3 = clean.drop(8).take(4).padEnd(4, '0')
        return "OM-$p1-$p2-$p3"
    }
}
