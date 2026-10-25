package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.AppSettings
import com.example.BuildConfig
import com.example.GitHubUpdateManager
import com.example.LogSeverity
import com.example.MyAccessibilityService
import com.example.PermissionUtils
import com.example.RestrictedSettingsGuideDialog
import com.example.RestrictedSettingsNoticeBanner
import com.example.TextAnalysisEngine
import com.example.UpdateResult
import com.example.ui.components.AccessibilityStatusBanner
import com.example.ui.components.ActivityLogsSection
import com.example.ui.components.DecisionThresholdsCard
import com.example.ui.components.MasterSwitchCard
import com.example.ui.components.PermissionSummaryCard
import com.example.ui.components.RideSimulatorCard
import com.example.ui.components.VoiceAnnouncerCard
import com.example.ui.theme.PrimaryEmerald
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    isServiceActive: Boolean,
    onRefreshStatus: () -> Unit,
    onNavigateToPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsState by AppSettings.settingsState.collectAsState()
    val logs by AppSettings.logsFlow.collectAsState()

    // Dynamic check for overlay and battery optimization
    val isOverlayGranted = remember(isServiceActive) { PermissionUtils.canDrawOverlays(context) }
    val isBatteryIgnored = remember(isServiceActive) { PermissionUtils.isIgnoringBatteryOptimizations(context) }
    val isReadyForAutoAccept = isServiceActive && isOverlayGranted && isBatteryIgnored
    val coroutineScope = rememberCoroutineScope()
    var showRestrictedSettingsDialog by remember { mutableStateOf(false) }

    // Lifecycle observer to trigger refresh whenever ON_RESUME occurs
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PrimaryEmerald),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ElectricBolt,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Captain Auto-Accept",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Ride Request Automation",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()
                            coroutineScope.launch {
                                val result = GitHubUpdateManager.checkForUpdates(
                                    context = context,
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    currentVersionCode = BuildConfig.VERSION_CODE,
                                    ignoreSkipped = true,
                                    notifySystem = true
                                )
                                when (result) {
                                    is UpdateResult.NoUpdate -> {
                                        Toast.makeText(context, "App is up to date (v${result.currentVersion})", Toast.LENGTH_SHORT).show()
                                    }
                                    is UpdateResult.Error -> {
                                        Toast.makeText(context, "Update check failed: ${result.message}", Toast.LENGTH_LONG).show()
                                    }
                                    else -> Unit
                                }
                            }
                        },
                        modifier = Modifier.testTag("check_for_updates_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Check for Updates",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNavigateToPermissions,
                        modifier = Modifier.testTag("open_permissions_screen_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Permissions & System Setup",
                            tint = if (isReadyForAutoAccept) PrimaryEmerald else MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = {
                            onRefreshStatus()
                            Toast.makeText(context, "Status refreshed", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("refresh_status_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Status",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Accessibility Service Status Banner (refreshes dynamically on resume)
            item {
                AccessibilityStatusBanner(
                    isActive = isServiceActive,
                    onOpenSettings = {
                        if (PermissionUtils.isAndroid13OrHigher()) {
                            showRestrictedSettingsDialog = true
                        } else {
                            try {
                                context.startActivity(AppSettings.createAccessibilitySettingsIntent())
                            } catch (e: Exception) {
                                Toast.makeText(context, "Unable to open Settings: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShowRestrictedGuide = {
                        showRestrictedSettingsDialog = true
                    }
                )
            }

            // Android 13/14+ Restricted Settings Help Notice
            if (!isServiceActive && PermissionUtils.isAndroid13OrHigher()) {
                item {
                    RestrictedSettingsNoticeBanner(
                        onClick = { showRestrictedSettingsDialog = true }
                    )
                }
            }

            // Quick Permission Overview Card with direct shortcut to Permissions Screen
            item {
                PermissionSummaryCard(
                    isReady = isReadyForAutoAccept,
                    isA11y = isServiceActive,
                    isOverlay = isOverlayGranted,
                    isBattery = isBatteryIgnored,
                    onManagePermissions = onNavigateToPermissions
                )
            }

            // 2. Master Automation Switch Card
            item {
                MasterSwitchCard(
                    isEnabled = settingsState.isAutoAcceptEnabled,
                    onToggle = { enabled ->
                        AppSettings.setAutoAcceptEnabled(context, enabled)
                        AppSettings.addLog(
                            title = "Master Switch",
                            message = if (enabled) "Auto-Accept logic enabled." else "Auto-Accept logic paused by captain.",
                            severity = if (enabled) LogSeverity.INFO else LogSeverity.WARNING
                        )
                    }
                )
            }

            // 3. Threshold Filters Card (Fare & Pickup Distance)
            item {
                DecisionThresholdsCard(
                    settings = settingsState,
                    onMinFareChanged = { AppSettings.setMinFare(context, it) },
                    onMaxFareChanged = { AppSettings.setMaxFare(context, it) },
                    onMaxDistanceChanged = { AppSettings.setMaxPickupDistance(context, it) },
                    onDelayChanged = { AppSettings.setClickDelayMs(context, it) }
                )
            }

            // 4. Voice Announcer (Text-to-Speech) Settings Card
            item {
                VoiceAnnouncerCard(
                    isEnabled = settingsState.isVoiceAnnouncerEnabled,
                    selectedLanguage = settingsState.voiceLanguage,
                    onToggle = { enabled ->
                        AppSettings.setVoiceAnnouncerEnabled(context, enabled)
                        AppSettings.addLog(
                            title = "Voice Announcer",
                            message = if (enabled) "Voice announcements activated." else "Voice announcements paused.",
                            severity = LogSeverity.INFO
                        )
                    },
                    onSelectLanguage = { lang ->
                        AppSettings.setVoiceLanguage(context, lang)
                        MyAccessibilityService.instance?.applyTtsLanguage()
                        AppSettings.addLog(
                            title = "Voice Language Updated",
                            message = "Voice announcer set to ${if (lang == "hi") "Hindi (हिंदी)" else "English"}.",
                            severity = LogSeverity.INFO
                        )
                    },
                    onTestVoice = {
                        val activeService = MyAccessibilityService.instance
                        if (activeService != null) {
                            activeService.testAnnouncement()
                            Toast.makeText(context, "Speaking test announcement...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Enable Accessibility Service in system settings to hear live voice announcements.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }

            // 5. Interactive Ride Request Sandbox / Simulator
            item {
                RideSimulatorCard(
                    settings = settingsState,
                    onSimulateAccept = { offerText ->
                        val parsed = TextAnalysisEngine.parseRideOffer(offerText)
                        val eval = TextAnalysisEngine.evaluateRideOffer(
                            offer = parsed,
                            minFare = settingsState.minFare,
                            maxFare = settingsState.maxFare,
                            maxPickupDistance = settingsState.maxPickupDistance,
                            isAutoAcceptEnabled = settingsState.isAutoAcceptEnabled
                        )

                        // Trigger voice announcement in simulator if service is connected and voice is enabled
                        MyAccessibilityService.instance?.let { service ->
                            if (settingsState.isVoiceAnnouncerEnabled) {
                                service.announceNewRide(parsed)
                                if (eval.isAccepted) {
                                service.announceRideAccepted(eval.totalCurrency, eval.distanceKm)
                                } else {
                                    service.announceRideSkipped(eval.decisionReason)
                                }
                            }
                        }

                        if (eval.isAccepted) {
                            AppSettings.addLog(
                                title = "Simulation: Auto-Accepted!",
                                message = "Test offer meets all criteria. Action click dispatched in ${settingsState.clickDelayMs}ms.",
                                severity = LogSeverity.CLICK_EXECUTED,
                                evaluation = eval
                            )
                        } else {
                            AppSettings.addLog(
                                title = "Simulation: Rejected",
                                message = eval.decisionReason,
                                severity = LogSeverity.REJECTED,
                                evaluation = eval
                            )
                        }
                    }
                )
            }

            // 6. Real-Time Activity Log Feed
            item {
                ActivityLogsSection(
                    logs = logs,
                    onClearLogs = { AppSettings.clearLogs() }
                )
            }
        }
    }

    if (showRestrictedSettingsDialog) {
        RestrictedSettingsGuideDialog(
            onDismiss = { showRestrictedSettingsDialog = false },
            onOpenAppInfo = {
                PermissionUtils.openAppInfoSettings(context)
            },
            onOpenAccessibility = {
                try {
                    context.startActivity(AppSettings.createAccessibilitySettingsIntent())
                } catch (e: Exception) {
                    Toast.makeText(context, "Unable to open Settings: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
