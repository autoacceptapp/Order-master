package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * System notification manager for In-App Updates.
 *
 * Handles:
 * - Creation of dedicated High-Priority Notification Channel (APP_UPDATE_CHANNEL).
 * - Posting heads-up / system notifications when a new GitHub release is detected.
 * - Deep linking PendingIntent to launch MainActivity and automatically show UpdateDialog.
 * - Ongoing progress notification during APK background downloading.
 * - Installation prompt notification upon successful APK download.
 */
object UpdateNotificationManager {

    private const val TAG = "UpdateNotificationMgr"

    // Dedicated Notification Channel for App Updates
    const val CHANNEL_ID = "APP_UPDATE_CHANNEL"
    private const val CHANNEL_NAME = "Order Master Updates"
    private const val CHANNEL_DESCRIPTION = "Alerts and progress notifications for new application updates"

    // Notification IDs
    const val NOTIFICATION_ID_UPDATE_AVAILABLE = 9001
    const val NOTIFICATION_ID_DOWNLOAD_PROGRESS = 9002

    private const val REQ_CODE_UPDATE_CLICK = 9101
    private const val REQ_CODE_INSTALL_CLICK = 9102

    /**
     * Initializes and registers the notification channel on Android 8.0+ (API 26+).
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null) {
                val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
                if (existingChannel == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = CHANNEL_DESCRIPTION
                        enableLights(true)
                        enableVibration(true)
                        setShowBadge(true)
                    }
                    notificationManager.createNotificationChannel(channel)
                    Log.d(TAG, "Notification channel $CHANNEL_ID registered successfully")
                }
            }
        }
    }

    /**
     * Checks whether notifications can be posted (respects POST_NOTIFICATIONS permission on Android 13+).
     */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Triggers a persistent/high-priority System Notification when a new release is detected.
     *
     * @param context Application context
     * @param latestVersion Version string (e.g., "debug-apk-build-7-1" or "v1.2.0")
     * @param releaseNotes Optional changelog notes summary
     */
    fun showUpdateAvailableNotification(
        context: Context,
        latestVersion: String,
        releaseNotes: String? = null
    ) {
        createNotificationChannel(context)

        if (!canPostNotifications(context)) {
            Log.w(TAG, "Cannot post update notification: POST_NOTIFICATIONS not granted")
            return
        }

        // PendingIntent to launch MainActivity and immediately surface the Update Dialog
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SHOW_UPDATE_DIALOG, true)
            putExtra(MainActivity.EXTRA_LATEST_VERSION, latestVersion)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQ_CODE_UPDATE_CLICK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = "Version $latestVersion is out. Tap to update now."
        val bigText = if (!releaseNotes.isNullOrBlank()) {
            "Version $latestVersion is available.\n\nWhat's New:\n$releaseNotes"
        } else {
            bodyText
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("New Order Master Update Available!")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_UPDATE_AVAILABLE, notification)
            Log.i(TAG, "Update available notification posted for version: $latestVersion")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while posting notification", e)
        }
    }

    /**
     * Updates or shows an ongoing notification tracking APK download progress.
     */
    fun showDownloadProgressNotification(
        context: Context,
        progressPercent: Int,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        createNotificationChannel(context)

        if (!canPostNotifications(context)) return

        val isIndeterminate = progressPercent < 0
        val contentText = if (!isIndeterminate) {
            "$progressPercent% downloaded"
        } else {
            "Downloading update package..."
        }

        // Tap opens MainActivity so user can see in-app progress
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SHOW_UPDATE_DIALOG, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQ_CODE_UPDATE_CLICK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading Order Master Update")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setProgress(100, if (isIndeterminate) 0 else progressPercent, isIndeterminate)

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DOWNLOAD_PROGRESS, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException posting download progress notification", e)
        }
    }

    /**
     * Posts a notification when the APK download finishes, prompting installation immediately on click.
     */
    fun showDownloadCompleteNotification(context: Context, apkFile: File) {
        createNotificationChannel(context)

        // Cancel progress notification
        dismissNotification(context, NOTIFICATION_ID_DOWNLOAD_PROGRESS)

        if (!canPostNotifications(context)) return

        // PendingIntent to invoke install intent directly from the notification
        val installIntent = try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create install intent for notification", e)
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_SHOW_UPDATE_DIALOG, true)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQ_CODE_INSTALL_CLICK,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Order Master Update Ready")
            .setContentText("Download completed. Tap to install now.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_UPDATE_AVAILABLE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException posting download complete notification", e)
        }
    }

    /**
     * Dismisses a specific notification ID.
     */
    fun dismissNotification(context: Context, notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss notification $notificationId", e)
        }
    }

    /**
     * Clears all update-related notifications.
     */
    fun clearAllUpdateNotifications(context: Context) {
        dismissNotification(context, NOTIFICATION_ID_UPDATE_AVAILABLE)
        dismissNotification(context, NOTIFICATION_ID_DOWNLOAD_PROGRESS)
    }
}
