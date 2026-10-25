package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.CaptainAutoAcceptTheme
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val EXTRA_SHOW_UPDATE_DIALOG = "extra_show_update_dialog"
        const val EXTRA_LATEST_VERSION = "extra_latest_version"
    }

    // Reactive state flow to reflect accessibility service state dynamically
    private val isServiceActiveFlow = MutableStateFlow(false)

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AppSettings.EXTRA_LOG_MESSAGE)
            if (!msg.isNullOrBlank()) {
                AppSettings.addLog(
                    title = "System Broadcast",
                    message = msg,
                    severity = LogSeverity.INFO
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.init(this)
        LicenseManager.init(this)
        refreshServiceStatus()

        // Strict Real-Time Firebase Session Check on app launch
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (com.google.firebase.FirebaseApp.getApps(applicationContext).isEmpty()) {
                    Log.w(TAG, "FirebaseApp is not initialized; skipping session check.")
                    return@launch
                }
                val auth = FirebaseAuth.getInstance()
                val user = auth.currentUser
                if (user != null) {
                    try {
                        user.reload().await()
                    } catch (e: Exception) {
                        // reload() throws an exception if the user was deleted or the project is dead
                        withContext(Dispatchers.Main) {
                            AppSettings.signOut(applicationContext)
                            PassManager.setPassStatus(applicationContext, false)
                            AppSettings.setPassExpiryTimestamp(applicationContext, 0L)
                            Toast.makeText(
                                applicationContext,
                                "Session Expired or Account Deleted. Logged out.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    // currentUser is null: ensure local cache matches the logged-out state
                    withContext(Dispatchers.Main) {
                        AppSettings.signOut(applicationContext)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Firebase session check: ${e.message}", e)
            }
        }

        // 3. Server-Side / Expiry Date Check: Verify pass status with server on app launch
        lifecycleScope.launch {
            PassManager.verifyServerPassStatus(applicationContext)
        }

        // Ensure update notification channel is registered
        UpdateNotificationManager.createNotificationChannel(this)
        handleUpdateIntent(intent)

        // Automatically check for new releases in the background when app launches
        GitHubUpdateManager.autoCheckForUpdates(this, lifecycleScope)

        setContent {
            CaptainAutoAcceptTheme {
                val updateState by GitHubUpdateManager.updateState.collectAsState()
                val isUpdateDialogVisible by GitHubUpdateManager.isDialogVisible.collectAsState()

                // Display In-App Auto Update Dialog when a new release is available
                if (isUpdateDialogVisible && updateState is UpdateResult.UpdateAvailable) {
                    val updateInfo = updateState as UpdateResult.UpdateAvailable
                    UpdateDialog(
                        updateInfo = updateInfo,
                        onDismissRequest = {
                            GitHubUpdateManager.dismissDialog()
                        },
                        onLater = {
                            GitHubUpdateManager.dismissDialog()
                        },
                        onIgnore = { versionTag ->
                            GitHubUpdateManager.ignoreVersion(applicationContext, versionTag)
                        }
                    )
                }

                com.example.ui.OrderMasterApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically refreshes the accessibility service status and syncs overlay
        refreshServiceStatus()
        LicenseManager.refreshAccessStatus()
        AppSettings.syncOverlayService(this)

        // 3. Server-Side / Expiry Date Check on resume (chahe Accessibility ON ho ya OFF)
        lifecycleScope.launch {
            PassManager.verifyServerPassStatus(applicationContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE_DIALOG, false) == true) {
            GitHubUpdateManager.showDialog()
        }
    }

    private fun refreshServiceStatus() {
        isServiceActiveFlow.value = AppSettings.isAccessibilityServiceEnabled(this)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AppSettings.ACTION_ACCESSIBILITY_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(logReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }
}
