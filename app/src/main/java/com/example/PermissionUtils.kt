package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Structured model representing an individual permission / system configuration item,
 * its dynamic granted status, importance, and intent launcher.
 */
data class AppPermissionItem(
    val id: String,
    val title: String,
    val description: String,
    val isCritical: Boolean,
    val isGranted: Boolean,
    val requiresRuntimeAction: Boolean = true,
    val category: PermissionCategory = PermissionCategory.SYSTEM
)

enum class PermissionCategory {
    CORE_SERVICE,
    BACKGROUND_POWER,
    OVERLAY_AND_UI,
    SYSTEM
}

/**
 * Production-ready Permission & System Settings utility object.
 *
 * Provides:
 * 1. Robust dynamic permission checks (Accessibility, Overlay, Battery Optimizations,
 *    Notifications, Exact Alarms, WakeLock).
 * 2. Intent builders to launch specific OEM & Android system settings pages
 *    (Xiaomi/MIUI, Oppo/ColorOS, Vivo/Funtouch, Huawei, Transsion, and Android Stock).
 */
object PermissionUtils {

    const val TAG = "PermissionUtils"

    // =========================================================================================
    // DYNAMIC PERMISSION CHECKERS
    // =========================================================================================

    /**
     * Checks if our Accessibility Service is currently active in the Android system.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return AppSettings.isAccessibilityServiceEnabled(context)
    }

    /**
     * Checks if the app has permission to draw overlay windows (SYSTEM_ALERT_WINDOW).
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Checks if the app is whitelisted from Doze / Battery Saver optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    /**
     * Checks if post-notifications permission is granted (Android 13+).
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.areNotificationsEnabled() ?: true
        }
    }

    /**
     * Checks if exact alarm scheduling is allowed (Android 12+).
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: true
        } else {
            true
        }
    }

    /**
     * Checks if normal install-time permissions (WAKE_LOCK, VIBRATE) are present.
     */
    fun hasNormalPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Compiles a full list of permission items with live granted status for the UI.
     */
    fun getPermissionStatusList(context: Context): List<AppPermissionItem> {
        val isA11y = isAccessibilityServiceEnabled(context)
        val isOverlay = canDrawOverlays(context)
        val isBattery = isIgnoringBatteryOptimizations(context)
        val isNotif = areNotificationsEnabled(context)
        val isAlarm = canScheduleExactAlarms(context)

        return listOf(
            AppPermissionItem(
                id = "accessibility",
                title = "Accessibility Service",
                description = "Required to inspect ride cards and automatically execute the Accept tap.",
                isCritical = true,
                isGranted = isA11y,
                category = PermissionCategory.CORE_SERVICE
            ),
            AppPermissionItem(
                id = "overlay",
                title = "Display Over Other Apps",
                description = "Enables floating automation controls and status overlays on top of driver apps.",
                isCritical = true,
                isGranted = isOverlay,
                category = PermissionCategory.OVERLAY_AND_UI
            ),
            AppPermissionItem(
                id = "battery",
                title = "Unrestricted Battery / Ignore Doze",
                description = "Prevents Android from freezing or killing the background service during active shifts.",
                isCritical = true,
                isGranted = isBattery,
                category = PermissionCategory.BACKGROUND_POWER
            ),
            AppPermissionItem(
                id = "autostart",
                title = "OEM Auto-Start / Background Run",
                description = "Special manufacturer background launch permission (MIUI, ColorOS, Funtouch, EMUI).",
                isCritical = false,
                isGranted = false, // Cannot be read directly on Android, user-verified
                category = PermissionCategory.BACKGROUND_POWER
            ),
            AppPermissionItem(
                id = "notifications",
                title = "System Notifications",
                description = "Shows real-time foreground status, match alerts, and accepted ride confirmations.",
                isCritical = false,
                isGranted = isNotif,
                category = PermissionCategory.SYSTEM
            ),
            AppPermissionItem(
                id = "exact_alarm",
                title = "Exact Alarms & Timers",
                description = "Ensures exact reaction delays and shift countdowns execute with zero drift.",
                isCritical = false,
                isGranted = isAlarm,
                category = PermissionCategory.SYSTEM
            )
        )
    }

    /**
     * Returns true if all critical permissions (Accessibility, Overlay, Battery) are satisfied.
     */
    fun isReadyForAutoAcceptance(context: Context): Boolean {
        return isAccessibilityServiceEnabled(context) &&
                canDrawOverlays(context) &&
                isIgnoringBatteryOptimizations(context)
    }

    // =========================================================================================
    // INTENT LAUNCHERS FOR ANDROID SYSTEM SETTING PAGES
    // =========================================================================================

    /**
     * Launches Android Accessibility Settings page directly.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        safeStartActivity(context, intent)
    }

    /**
     * Launches the "Display Over Other Apps" / Overlay permission page for this app.
     */
    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!safeStartActivity(context, intent)) {
                // Fallback to general overlay list
                safeStartActivity(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } else {
            openAppDetailsSettings(context)
        }
    }

    /**
     * Launches the Battery Optimization prompt or settings page.
     */
    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Direct request dialog if permission is declared
            val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!safeStartActivity(context, requestIntent)) {
                // Fallback to the battery optimization list
                val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (!safeStartActivity(context, listIntent)) {
                    openAppDetailsSettings(context)
                }
            }
        } else {
            openAppDetailsSettings(context)
        }
    }

    /**
     * Launches App Notification Settings.
     */
    fun openNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!safeStartActivity(context, intent)) {
                openAppDetailsSettings(context)
            }
        } else {
            openAppDetailsSettings(context)
        }
    }

    /**
     * Launches Exact Alarm Settings (Android 12+ / API 31+).
     */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!safeStartActivity(context, intent)) {
                openAppDetailsSettings(context)
            }
        } else {
            openAppDetailsSettings(context)
        }
    }

    /**
     * Comprehensive OEM Auto-Start & Background Management Handler.
     * Attempts vendor-specific activities for:
     * - Xiaomi (MIUI / HyperOS)
     * - Oppo / Realme (ColorOS)
     * - Vivo / iQOO (FuntouchOS / OriginOS)
     * - Huawei / Honor (EMUI / MagicUI)
     * - Transsion (Tecno / Infinix / Itel)
     * - Fallback: Standard Android App Info page
     */
    fun openOemAutoStartSettings(context: Context): Boolean {
        val oemIntents = listOf(
            // Xiaomi / Poco / Redmi (MIUI & HyperOS)
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                )
            ),

            // Oppo / Realme (ColorOS)
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            ),

            // Vivo / iQOO (Funtouch OS)
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            ),

            // Huawei / Honor (EMUI)
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            ),

            // Transsion (Infinix / Tecno / Itel)
            Intent().setComponent(
                ComponentName(
                    "com.transsion.phonemanager",
                    "com.transsion.phonemanager.view.AutoRunListActivity"
                )
            ),

            // Samsung Device Care / Battery
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            )
        )

        for (intent in oemIntents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (safeStartActivity(context, intent)) {
                Log.d(TAG, "Successfully launched OEM autostart page: ${intent.component}")
                return true
            }
        }

        // Fallback: Open Android App Info Settings page
        Log.i(TAG, "OEM intent not found; falling back to App Details Settings")
        return openAppDetailsSettings(context)
    }

    /**
     * Standard Android Application Details Settings fallback.
     */
    fun openAppDetailsSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return safeStartActivity(context, intent)
    }

    private fun safeStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) != null ||
                intent.component != null ||
                intent.action != null
            ) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start intent: ${intent.action ?: intent.component}", e)
            false
        }
    }
}
