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
     * Checks if the device is running Android 13 (Tiramisu, API 33) or higher,
     * where Android enforces "Restricted Settings" on sideloaded APK accessibility services.
     */
    fun isAndroid13OrHigher(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Checks if a specific Accessibility Service class is currently active in the Android system.
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        return AppSettings.isAccessibilityServiceEnabled(context, serviceClass)
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
     * Checks if the app can install unknown packages (Android 8.0+).
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
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
        val isInstaller = canRequestPackageInstalls(context)

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
                id = "notifications",
                title = "System Notifications",
                description = "Shows real-time foreground status, match alerts, and accepted ride confirmations.",
                isCritical = false,
                isGranted = isNotif,
                category = PermissionCategory.SYSTEM
            ),
            AppPermissionItem(
                id = "installer",
                title = "Install Unknown Apps / Auto-Updater",
                description = "Enables seamless in-app installation of new captain APK updates.",
                isCritical = false,
                isGranted = isInstaller,
                category = PermissionCategory.SYSTEM
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
     * Launches Android Accessibility Settings page directly to activate the accessibility service.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        safeStartActivity(context, intent)
    }

    /**
     * Specifically invokes RapidoAccessibilityService activation flow.
     */
    fun invokeRapidoAccessibilityService(context: Context) {
        openAccessibilitySettings(context)
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
     * Launches Unknown App Sources (APK installer) permission page for this app.
     */
    fun openInstallUnknownAppsSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
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
     * Checks if the current device runs on a Chinese OEM ROM (Xiaomi, Vivo, Realme, Oppo, OnePlus, Transsion, Huawei)
     * which enforces aggressive background task kills and requires explicit Auto-Start permissions.
     */
    fun isChineseRomDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val targets = listOf(
            "xiaomi", "redmi", "poco",
            "oppo", "realme", "oneplus",
            "vivo", "iqoo",
            "huawei", "honor",
            "transsion", "tecno", "infinix", "itel",
            "meizu", "zte", "nubia"
        )
        return targets.any { manufacturer.contains(it) || brand.contains(it) }
    }

    /**
     * Specifically checks if the device manufacturer or brand matches Xiaomi, Vivo, Oppo, OnePlus, or Realme.
     * These OEMs aggressively kill background and accessibility services unless AutoStart and Recents lock are configured.
     */
    fun isAggressiveOemDevice(): Boolean {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val b = Build.BRAND.orEmpty().lowercase()
        val targets = listOf("xiaomi", "redmi", "poco", "vivo", "iqoo", "oppo", "oneplus", "realme")
        return targets.any { m.contains(it) || b.contains(it) }
    }

    /**
     * Returns a human-friendly display label of the OEM device brand.
     */
    fun getDeviceBrandName(): String {
        val brand = Build.BRAND.orEmpty()
        return if (brand.isNotBlank()) brand.replaceFirstChar { it.uppercase() } else "Device"
    }

    /**
     * Comprehensive OEM Auto-Start & Background Management Handler.
     * Aggressively covers Chinese ROMs (Xiaomi, Vivo, Realme, Oppo, OnePlus, Transsion, Huawei)
     * with prioritized explicit component intents to ensure the Accessibility Service is not killed.
     */
    fun openOemAutoStartSettings(context: Context): Boolean {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val b = Build.BRAND.orEmpty().lowercase()

        // 1. Xiaomi / Redmi / POCO (MIUI & HyperOS - com.miui.securitycenter & com.miui.powerkeeper)
        val xiaomiIntents = listOf(
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivityV2")
            ),
            Intent("miui.intent.action.OP_AUTO_START").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
            ),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerManageActivity")
            ),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerOptimizationActivity")
            ),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            ).apply {
                putExtra("extra_pkgname", context.packageName)
            },
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            ).apply {
                putExtra("extra_pkgname", context.packageName)
            },
            Intent().setComponent(
                ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            ).apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
            },
            Intent().setComponent(
                ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")
            ),
            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity")
            ),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.appmanager.AppManagerMainActivity")
            )
        )

        // 2. Oppo / Realme / OnePlus (ColorOS / Realme UI / OxygenOS - com.coloros.safecenter & com.oplus.safecenter)
        val oppoRealmeIntents = listOf(
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startupapp.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startupmanager.StartupBatchManageActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startupapp.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startupmanager.StartupBatchManageActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startupapp.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oplus.battery", "com.oplus.battery.AppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oplus.battery", "com.oplus.battery.BatteryActivity")
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.singlepage.PermissionSinglePageActivity")
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity")
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startupInit.StartupNormalAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity")
            ),
            Intent("coloros.safecenter.permission.startup.StartupAppListActivity"),
            Intent("coloros.safecenter.permission.startupapp.StartupAppListActivity"),
            Intent().setComponent(
                ComponentName("com.coloros.battery", "com.coloros.battery.BatteryActivity")
            ),
            Intent().setComponent(
                ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            )
        )

        // 3. Vivo / iQOO (Funtouch OS / OriginOS - com.iqoo.secure & com.vivo.permissionmanager)
        val vivoIntents = listOf(
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            ),
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")
            ),
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.SoftwareManagerActivity")
            ),
            Intent().setComponent(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            ),
            Intent().setComponent(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
            ),
            Intent().setComponent(
                ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
            ),
            Intent().setComponent(
                ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerControlActivity")
            ),
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.MainGuideActivity")
            ),
            Intent().setComponent(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
            ).apply {
                putExtra("packagename", context.packageName)
            }
        )

        // 4. Transsion (Tecno / Infinix / Itel - HiOS / XOS)
        val transsionIntents = listOf(
            Intent().setComponent(
                ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.view.AutoRunListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.view.AppPowerManagerActivity")
            ),
            Intent().setComponent(
                ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.activity.PowerSettingActivity")
            ),
            Intent().setComponent(
                ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.activity.AutoRunListActivity")
            )
        )

        // 5. Huawei / Honor (EMUI / MagicUI / HarmonyOS - com.huawei.systemmanager & com.hihonor.systemmanager)
        val huaweiIntents = listOf(
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            ),
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            ),
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")
            ),
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.mainscreen.MainScreenActivity")
            ),
            Intent().setComponent(
                ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")
            ),
            Intent().setComponent(
                ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity")
            )
        )

        // 6. Samsung (One UI)
        val samsungIntents = listOf(
            Intent().setComponent(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
            ),
            Intent().setComponent(
                ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            ),
            Intent().setComponent(
                ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity")
            )
        )

        // Build prioritized list: first put the intents that directly match the host manufacturer/brand
        val prioritizedIntents = mutableListOf<Intent>()
        val otherIntents = mutableListOf<Intent>()

        fun sortCategory(isMatch: Boolean, intents: List<Intent>) {
            if (isMatch) {
                prioritizedIntents.addAll(intents)
            } else {
                otherIntents.addAll(intents)
            }
        }

        val isXiaomi = m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")
        sortCategory(isXiaomi, xiaomiIntents)

        val isOppoRealme = m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ||
                b.contains("oppo") || b.contains("realme") || b.contains("oneplus")
        sortCategory(isOppoRealme, oppoRealmeIntents)

        val isVivo = m.contains("vivo") || m.contains("iqoo") ||
                b.contains("vivo") || b.contains("iqoo")
        sortCategory(isVivo, vivoIntents)

        val isTranssion = m.contains("transsion") || m.contains("tecno") || m.contains("infinix") || m.contains("itel") ||
                b.contains("transsion") || b.contains("tecno") || b.contains("infinix") || b.contains("itel")
        sortCategory(isTranssion, transsionIntents)

        val isHuawei = m.contains("huawei") || m.contains("honor") ||
                b.contains("huawei") || b.contains("honor")
        sortCategory(isHuawei, huaweiIntents)

        val isSamsung = m.contains("samsung") || b.contains("samsung")
        sortCategory(isSamsung, samsungIntents)

        val candidates = prioritizedIntents + otherIntents

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (safeStartActivity(context, intent)) {
                Log.d(TAG, "Successfully launched OEM autostart page: ${intent.component ?: intent.action}")
                return true
            }
        }

        // Fallback: Open Android App Info Settings page
        Log.i(TAG, "No OEM autostart intent matched or resolved; falling back to App Details Settings")
        return openAppDetailsSettings(context)
    }

    /**
     * Standard Android Application Details Settings fallback.
     */
    fun openAppDetailsSettings(context: Context): Boolean {
        return openAppInfoSettings(context)
    }

    /**
     * Directly triggers Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
     * with Uri.fromParts("package", packageName, null) to open App Info.
     */
    fun openAppInfoSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return safeStartActivity(context, intent)
    }

    /**
     * Safely attempts to launch an intent, catching any ActivityNotFoundException, SecurityException,
     * or other OS runtime error on stock Android or non-matching OEM devices.
     */
    private fun safeStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            val resolved = intent.resolveActivity(context.packageManager) != null
            if (resolved) {
                context.startActivity(intent)
                true
            } else if (intent.component != null) {
                // Some OEM security components do not advertise in resolveActivity but can still launch
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            // Guard against ActivityNotFoundException, SecurityException, and OEM permission rejections
            Log.w(TAG, "Failed to start intent: ${intent.action ?: intent.component}: ${e.message}")
            false
        }
    }
}
