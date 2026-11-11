package com.example

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.data.repository.AppConfig
import com.example.data.repository.AppConfigRepository
import androidx.lifecycle.lifecycleScope
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.CaptainAutoAcceptTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // Reactive state flow to reflect notification permission state
    private val isNotificationPermissionGranted = MutableStateFlow(true)

    // Android 13+ (API 33+) Runtime POST_NOTIFICATIONS launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isNotificationPermissionGranted.value = isGranted
        if (isGranted) {
            Log.i(TAG, "POST_NOTIFICATIONS runtime permission granted by user")
        } else {
            Log.w(TAG, "POST_NOTIFICATIONS runtime permission denied by user")
        }
    }

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
        GlobalAdminManager.init(this)
        InAppMessageManager.init(this)
        AppConfigRepository.init(this)

        // Safe AdMob SDK Initialization on the Main Thread
        try {
            com.google.android.gms.ads.MobileAds.initialize(this) { status ->
                Log.i(TAG, "AdMob SDK Initialized on Main Thread: $status")
                try {
                    RewardedAdManager.loadAd(applicationContext)
                } catch (e: Throwable) {
                    Log.w(TAG, "Notice pre-caching RewardedAd after init: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Notice initializing AdMob SDK on Main Thread: ${e.message}")
        }

        isNotificationPermissionGranted.value = PermissionUtils.areNotificationsEnabled(this)
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
                    } catch (e: FirebaseAuthInvalidUserException) {
                        // User was explicitly deleted or disabled in Firebase Auth: enforce Zero-Trust sign out
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
                    } catch (e: Exception) {
                        // Network drop or temporary outage: preserve user session and driver state
                        Log.w(TAG, "Firebase session check failed (likely network drop): ${e.message}")
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
        requestNotificationPermissionIfNeeded()
        handleUpdateIntent(intent)
        handleDeepLinkIntent(intent)

        // Automatically check for new releases in the background when app launches
        GitHubUpdateManager.autoCheckForUpdates(this, lifecycleScope)

        setContent {
            CaptainAutoAcceptTheme {
                val isLanguageSelected by AppSettings.isLanguageSelectedFlow.collectAsState()
                val isOnboardingCompleted by AppSettings.isOnboardingCompletedFlow.collectAsState()
                val accessStatus by LicenseManager.accessStatus.collectAsState()

                // Fail-safe splash timeout: Never hold user on loading screen for more than 2.5 seconds
                var isSplashTimedOut by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(2500L)
                    isSplashTimedOut = true
                    if (LicenseManager.accessStatus.value is AccessStatus.Loading) {
                        Log.w(TAG, "Splash loading timeout reached (2.5s); forcing transition to dashboard.")
                        LicenseManager.forceReleaseLoadingState()
                    }
                }

                if (!isLanguageSelected) {
                    SplashScreen(
                        isFirstLaunch = true,
                        onLanguageSelected = { selectedCode ->
                            AppSettings.setAppLanguage(applicationContext, selectedCode)
                        }
                    )
                } else if (!isOnboardingCompleted) {
                    OnboardingScreen(
                        onFinish = {
                            AppSettings.setOnboardingCompleted(applicationContext, true)
                        }
                    )
                } else if (accessStatus is AccessStatus.Loading && !isSplashTimedOut) {
                    SplashScreen(
                        isFirstLaunch = false,
                        onLanguageSelected = {}
                    )
                } else {
                    val appConfig by AppConfigRepository.observeAppConfig().collectAsState(initial = AppConfig())
                    val isForceUpdateRequired = BuildConfig.VERSION_CODE < appConfig.minSupportedVersionCode

                    // 1. Force Update Dialog (Strict, non-dismissible, blocks the app if version is too old)
                    if (isForceUpdateRequired) {
                    ForceUpdateDialog(
                        appConfig = appConfig,
                        onUpdateClick = { url ->
                            val trimmedUrl = url.trim()
                            if (trimmedUrl.endsWith(".apk", ignoreCase = true)) {
                                ApkDownloader.startDownload(
                                    context = applicationContext,
                                    downloadUrl = trimmedUrl,
                                    fileName = "OrderMaster-v${appConfig.minSupportedVersionCode}.apk"
                                )
                            } else if (trimmedUrl.isNotEmpty()) {
                                try {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmedUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(browserIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        applicationContext,
                                        "Cannot open update link: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                // Fallback to GitHub releases if URL is empty
                                try {
                                    val fallbackIntent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/autoacceptapp/Order-master/releases/latest")
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(fallbackIntent)
                                } catch (_: Exception) {}
                            }
                        }
                    )
                } else {
                    val updateState by GitHubUpdateManager.updateState.collectAsState()
                    val isUpdateDialogVisible by GitHubUpdateManager.isDialogVisible.collectAsState()

                    // Optional Auto Update Dialog (when a new optional GitHub release is available)
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

                    // In-App Global / User Message Dialog (Popup)
                    val activeMessage by InAppMessageManager.activeMessage.collectAsState()
                    if (activeMessage != null) {
                        val message = activeMessage!!
                        AlertDialog(
                            onDismissRequest = {
                                InAppMessageManager.dismissMessage(message.id)
                            },
                            properties = DialogProperties(
                                dismissOnBackPress = true,
                                dismissOnClickOutside = true,
                                usePlatformDefaultWidth = false
                            ),
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Campaign,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            title = {
                                Text(
                                    text = message.title,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.testTag("in_app_message_title")
                                )
                            },
                            text = {
                                Text(
                                    text = message.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("in_app_message_body")
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        InAppMessageManager.dismissMessage(message.id)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.testTag("in_app_message_dismiss_btn")
                                ) {
                                    Text("Got it / Dismiss", fontWeight = FontWeight.Bold)
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("in_app_message_dialog")
                        )
                    }

                    // System Notification Warning Dialog (Android 13+)
                    val areNotificationsEnabled by isNotificationPermissionGranted.collectAsState()
                    var isNotificationWarningDismissed by remember { mutableStateOf(false) }
                    if (!areNotificationsEnabled && !isNotificationWarningDismissed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        AlertDialog(
                            onDismissRequest = { isNotificationWarningDismissed = true },
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            title = {
                                Text(
                                    text = "Notifications Disabled",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.testTag("notification_warning_title")
                                )
                            },
                            text = {
                                Text(
                                    text = "Foreground service alerts, ride acceptance banners, and critical update alerts cannot run reliably in the background without notification permissions on Android 13+. Please enable notification access in App Settings.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("notification_warning_body")
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        PermissionUtils.openNotificationSettings(this@MainActivity)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.testTag("notification_warning_enable_btn")
                                ) {
                                    Text("Enable in Settings", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { isNotificationWarningDismissed = true },
                                    modifier = Modifier.testTag("notification_warning_dismiss_btn")
                                ) {
                                    Text("Dismiss")
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("notification_warning_dialog")
                        )
                    }

                    com.example.ui.OrderMasterApp()
                }
            }
        }
    }
}

    override fun onResume() {
        super.onResume()
        // Automatically refreshes the accessibility service status and syncs overlay
        refreshServiceStatus()
        isNotificationPermissionGranted.value = PermissionUtils.areNotificationsEnabled(this)
        LicenseManager.refreshAccessStatus()
        AppSettings.syncOverlayService(this)

        // 3. Server-Side / Expiry Date Check on resume (chahe Accessibility ON ho ya OFF)
        lifecycleScope.launch {
            PassManager.verifyServerPassStatus(applicationContext)
        }

        // Proactive Rewarded Ad Pre-Caching on resume
        try {
            if (!RewardedAdManager.isAdLoaded() && !RewardedAdManager.isLoading()) {
                RewardedAdManager.loadAd(applicationContext)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Notice pre-caching RewardedAd in onResume: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE_DIALOG, false) == true) {
            GitHubUpdateManager.showDialog()
        }
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val data = intent?.data ?: return
        // Captures https://ordermaster.app/invite?code={CODE} or ordermaster://invite?code={CODE}
        val code = data.getQueryParameter("code")
        if (!code.isNullOrBlank()) {
            val cleanCode = code.trim().uppercase(java.util.Locale.ROOT)
            ReferralManager.pendingInviteCode.value = cleanCode
            Log.i(TAG, "Deep link captured invite code: $cleanCode")
        }
    }

    private fun refreshServiceStatus() {
        isServiceActiveFlow.value = AppSettings.isAccessibilityServiceEnabled(this)
    }

    /**
     * Runtime request for POST_NOTIFICATIONS on Android 13+ (API 33+)
     * to ensure foreground service alerts, ride confirmations, and update alerts are delivered.
     * Prevents aggressive looping or freezing on cold starts if previously denied.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = PermissionUtils.areNotificationsEnabled(this)
            isNotificationPermissionGranted.value = isGranted
            if (isGranted) return

            val prefs = getSharedPreferences("app_permission_prefs", Context.MODE_PRIVATE)
            val hasRequestedBefore = prefs.getBoolean("has_requested_notification_permission", false)
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

            if (hasRequestedBefore && !shouldShowRationale) {
                // Permanently denied ("Don't ask again") - do not launch permission request automatically on cold boot
                Log.d(TAG, "POST_NOTIFICATIONS permanently denied; silently updating state without prompt loop.")
                isNotificationPermissionGranted.value = false
                return
            }

            // Only launch the request if it's the first time or if the rationale should be shown
            prefs.edit().putBoolean("has_requested_notification_permission", true).apply()
            Log.d(TAG, "Requesting POST_NOTIFICATIONS runtime permission on Android 13+...")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

/**
 * Strict, non-dismissible Force Update Dialog.
 * Blocks all back navigation and dismiss attempts until the user updates.
 */
@Composable
fun ForceUpdateDialog(
    appConfig: AppConfig,
    onUpdateClick: (String) -> Unit
) {
    val context = LocalContext.current
    val downloadState by ApkDownloader.downloadState.collectAsState()

    // Strictly disable back navigation
    BackHandler(enabled = true) {
        // Consumes back press: update is mandatory
    }

    // Auto-prompt installer when download completes
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.ReadyToInstall) {
            val file = (downloadState as DownloadState.ReadyToInstall).apkFile
            if (ApkDownloader.canRequestPackageInstalls(context)) {
                ApkDownloader.promptInstall(context, file)
            } else {
                try {
                    context.startActivity(ApkDownloader.createManageUnknownAppSourcesIntent(context))
                } catch (_: Exception) {}
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            // Strictly non-dismissible
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = "Force Update",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Update Required",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("force_update_title")
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "A critical update is required to continue using Order Master. Your current version (v${BuildConfig.VERSION_NAME}, code ${BuildConfig.VERSION_CODE}) is no longer supported. Please update to version code ${appConfig.minSupportedVersionCode} or newer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("force_update_message")
                )

                // Progress state during download
                when (val state = downloadState) {
                    is DownloadState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                progress = { state.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("force_update_progress_bar")
                            )
                            Text(
                                text = "Downloading update: ${state.progressPercent}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    is DownloadState.ReadyToInstall -> {
                        Text(
                            text = "Download complete. Tap Install Now to complete the update.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is DownloadState.Error -> {
                        Text(
                            text = "Download error: ${state.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (val state = downloadState) {
                is DownloadState.Downloading -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("force_update_now_btn")
                    ) {
                        Text("Downloading (${state.progressPercent}%)...")
                    }
                }
                is DownloadState.ReadyToInstall -> {
                    Button(
                        onClick = {
                            if (ApkDownloader.canRequestPackageInstalls(context)) {
                                ApkDownloader.promptInstall(context, state.apkFile)
                            } else {
                                context.startActivity(ApkDownloader.createManageUnknownAppSourcesIntent(context))
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("force_update_now_btn")
                    ) {
                        Text("Install Now", fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            onUpdateClick(appConfig.dynamicAppDownloadUrl)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("force_update_now_btn")
                    ) {
                        Text("Update Now", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.testTag("force_update_dialog")
    )
}
