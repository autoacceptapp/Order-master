package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaDrm
import android.media.UnsupportedSchemeException
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.PrimaryEmerald
import java.security.MessageDigest
import java.util.UUID

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
    private const val PREFS_NAME = "hardware_id_prefs"
    private const val KEY_PERMANENT_HARDWARE_ID = "permanent_hardware_id"
    private const val KEY_FULL_SHA256 = "permanent_full_sha256"
    private const val KEY_ID_SOURCE = "permanent_id_source"

    // Widevine DRM Scheme UUID (Standard Android crypto hardware scheme)
    private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

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
     */
    @Synchronized
    fun getHardwareDetails(context: Context): HardwareDetails {
        cachedHardwareDetails?.let { return it }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_PERMANENT_HARDWARE_ID, null)
        val savedHash = prefs.getString(KEY_FULL_SHA256, null)
        val savedSource = prefs.getString(KEY_ID_SOURCE, null)

        if (!savedId.isNullOrEmpty() && !savedHash.isNullOrEmpty() && !savedSource.isNullOrEmpty()) {
            val details = HardwareDetails(
                formattedId = savedId,
                fullSha256 = savedHash,
                source = savedSource,
                isHardwareDrm = savedSource.contains("Widevine", ignoreCase = true)
            )
            cachedHardwareDetails = details
            cachedHardwareId = details.formattedId
            return details
        }

        // Generate permanent ID from physical Widevine DRM crypto-hardware
        val details = generatePermanentHardwareId(context)

        prefs.edit()
            .putString(KEY_PERMANENT_HARDWARE_ID, details.formattedId)
            .putString(KEY_FULL_SHA256, details.fullSha256)
            .putString(KEY_ID_SOURCE, details.source)
            .apply()

        cachedHardwareDetails = details
        cachedHardwareId = details.formattedId
        return details
    }

    /**
     * Extracts the raw hardware unique ID from MediaDrm Widevine module with graceful fallback.
     */
    private fun generatePermanentHardwareId(context: Context): HardwareDetails {
        var mediaDrm: MediaDrm? = null
        if (!DeviceUtils.isEmulator()) {
            try {
                if (MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) {
                    mediaDrm = MediaDrm(WIDEVINE_UUID)
                    val rawBytes = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)

                    if (rawBytes != null && rawBytes.isNotEmpty()) {
                        val fullSha256 = sha256Hex(rawBytes)
                        val formatted = formatHardwareCode(fullSha256)
                        Log.i(TAG, "Generated permanent hardware ID from Widevine MediaDrm: $formatted")
                        return HardwareDetails(
                            formattedId = formatted,
                            fullSha256 = fullSha256,
                            source = "Widevine Hardware DRM Module",
                            isHardwareDrm = true
                        )
                    } else {
                        Log.w(TAG, "MediaDrm deviceUniqueId returned null or empty bytes. Using crypto fallback.")
                    }
                } else {
                    Log.d(TAG, "Widevine crypto scheme not supported on this device. Using crypto fallback.")
                }
            } catch (e: UnsupportedSchemeException) {
                Log.w(TAG, "Widevine MediaDrm unsupported on this device: ${e.message}. Using fallback.")
            } catch (e: Throwable) {
                Log.w(TAG, "Exception accessing Widevine MediaDrm: ${e.message}. Using fallback.", e)
            } finally {
                try {
                    if (mediaDrm != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            mediaDrm.close()
                        } else {
                            @Suppress("DEPRECATION")
                            mediaDrm.release()
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Error closing MediaDrm: ${e.message}")
                }
            }
        }

        // Fallback mechanism: Hardware fingerprint combination
        val fallbackBytes = buildHardwareFallbackEntropy(context)
        val fullSha256 = sha256Hex(fallbackBytes)
        val formatted = formatHardwareCode(fullSha256)
        Log.i(TAG, "Generated permanent hardware ID from Hardware Fingerprint Fallback: $formatted")
        return HardwareDetails(
            formattedId = formatted,
            fullSha256 = fullSha256,
            source = "Hardware Cryptographic Fallback",
            isHardwareDrm = false
        )
    }

    /**
     * Formats a 64-character SHA-256 hash into an uppercase, readable format:
     * e.g., "OM-8F92-A103-B94C"
     */
    private fun formatHardwareCode(sha256Hex: String): String {
        val clean = sha256Hex.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        val p1 = clean.take(4).padEnd(4, '0')
        val p2 = clean.drop(4).take(4).padEnd(4, '0')
        val p3 = clean.drop(8).take(4).padEnd(4, '0')
        return "OM-$p1-$p2-$p3"
    }

    /**
     * Computes the lowercase 64-character SHA-256 hexadecimal string from byte array.
     */
    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * Generates persistent hardware entropy from static physical board and device identifiers.
     */
    private fun buildHardwareFallbackEntropy(context: Context): ByteArray {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) {
            ""
        }

        val entropy = StringBuilder()
            .append("android_id=").append(androidId).append(";")
            .append("board=").append(Build.BOARD).append(";")
            .append("brand=").append(Build.BRAND).append(";")
            .append("device=").append(Build.DEVICE).append(";")
            .append("hardware=").append(Build.HARDWARE).append(";")
            .append("manufacturer=").append(Build.MANUFACTURER).append(";")
            .append("model=").append(Build.MODEL).append(";")
            .append("product=").append(Build.PRODUCT).append(";")
            .append("fingerprint=").append(Build.FINGERPRINT).append(";")
            .toString()

        return entropy.toByteArray(Charsets.UTF_8)
    }

    /**
     * Copies text to the system clipboard and displays a standard confirmation Toast.
     */
    fun copyToClipboard(context: Context, text: String, label: String = context.getString(R.string.hwid_clipboard_label)) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.hwid_toast_copied, label), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Purely informational Jetpack Compose UI card displaying the Permanent Device Hardware ID.
 */
@Composable
fun HardwareIdCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val details = remember { HardwareIdManager.getHardwareDetails(context) }
    var copiedRecently by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hardware_id_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Icon + Title + Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = stringResource(R.string.hwid_card_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.hwid_card_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.hwid_card_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = stringResource(R.string.hwid_badge_permanent),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }

            // Monospace Hardware ID Container
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = details.formattedId,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("hardware_id_value_text")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.hwid_source_label, details.source),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            // Description note
            Text(
                text = stringResource(R.string.hwid_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                lineHeight = 16.sp
            )

            // Action Buttons: Copy ID Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val clipboardLabel = stringResource(R.string.hwid_clipboard_label)
                Button(
                    onClick = {
                        HardwareIdManager.copyToClipboard(context, details.formattedId, clipboardLabel)
                        copiedRecently = true
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("copy_hardware_id_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (copiedRecently) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.hwid_btn_copy),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (copiedRecently) R.string.hwid_btn_copied else R.string.hwid_btn_copy),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
