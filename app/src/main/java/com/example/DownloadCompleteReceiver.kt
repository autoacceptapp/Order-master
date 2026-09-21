package com.example

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manifest-registered BroadcastReceiver for ACTION_DOWNLOAD_COMPLETE.
 *
 * Ensures that if the application was backgrounded or killed while downloading
 * a larger APK release, the completion event is still received by the OS,
 * verifying the downloaded APK and triggering the FileProvider installation intent.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            Log.i("DownloadCompleteReceiver", "Received ACTION_DOWNLOAD_COMPLETE for download ID: $downloadId")
            if (downloadId > 0) {
                ApkDownloader.handleExternalDownloadComplete(context.applicationContext, downloadId)
            }
        }
    }
}
