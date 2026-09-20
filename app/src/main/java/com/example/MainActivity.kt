package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.CaptainAutoAcceptTheme
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.theme.SurfaceStroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

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
        refreshServiceStatus()

        // Automatically check for new releases in the background when app launches
        lifecycleScope.launch {
            GitHubUpdateManager.checkForUpdates(
                context = applicationContext,
                currentVersion = BuildConfig.VERSION_NAME,
                ignoreSkipped = false
            )
        }

        setContent {
            CaptainAutoAcceptTheme {
                var currentScreen by rememberSaveable { mutableStateOf("dashboard") }
                val isServiceActive by isServiceActiveFlow.collectAsState()
                val updateState by GitHubUpdateManager.updateState.collectAsState()

                // Display In-App Auto Update Dialog when a new release is available
                if (updateState is UpdateResult.UpdateAvailable) {
                    val updateInfo = updateState as UpdateResult.UpdateAvailable
                    UpdateDialog(
                        updateInfo = updateInfo,
                        onDismissRequest = {
                            GitHubUpdateManager.resetState()
                        },
                        onSkipVersion = { versionTag ->
                            GitHubUpdateManager.skipVersion(applicationContext, versionTag)
                        }
                    )
                }

                if (currentScreen == "permissions") {
                    AppSettingsScreen(
                        onNavigateBack = { currentScreen = "dashboard" }
                    )
                } else {
                    DashboardScreen(
                        isServiceActive = isServiceActive,
                        onRefreshStatus = { refreshServiceStatus() },
                        onNavigateToPermissions = { currentScreen = "permissions" }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically refreshes the accessibility service status when the user
        // navigates back to the app from the system Accessibility Settings screen.
        refreshServiceStatus()
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
                                    ignoreSkipped = true
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
                        try {
                            context.startActivity(AppSettings.createAccessibilitySettingsIntent())
                        } catch (e: Exception) {
                            Toast.makeText(context, "Unable to open Settings: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
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
                            message = "Voice announcer set to ${if (lang == "hi") "Hindi (हिन्दी)" else "English"}.",
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
}

@Composable
fun AccessibilityStatusBanner(
    isActive: Boolean,
    onOpenSettings: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) PrimaryEmerald else MaterialTheme.colorScheme.error,
        label = "border_color"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .testTag("service_status_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isActive) PrimaryEmerald else MaterialTheme.colorScheme.error)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isActive) "Service Active & Monitoring" else "Accessibility Permission Inactive",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isActive) PrimaryEmerald else MaterialTheme.colorScheme.error
                    )
                }

                if (!isActive) {
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("enable_accessibility_button")
                    ) {
                        Text("Enable", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (isActive) {
                    "Background service is listening for ride request overlay cards (Rapido Captain) to evaluate fares and pickup distances."
                } else {
                    "Tap 'Enable' to open Android Accessibility settings and toggle 'Smart Text Notification & Analysis Service' to ON."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PermissionSummaryCard(
    isReady: Boolean,
    isA11y: Boolean,
    isOverlay: Boolean,
    isBattery: Boolean,
    onManagePermissions: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permission_summary_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = if (isReady) PrimaryEmerald else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Background Readiness",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Text(
                    text = if (isReady) "READY" else "ATTENTION",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isReady) PrimaryEmerald else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Three status badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // A11y Badge
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isA11y) PrimaryEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(8.dp)
                ) {
                    Column {
                        Text("Accessibility", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (isA11y) "Active" else "Missing",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isA11y) PrimaryEmerald else MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Overlay Badge
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isOverlay) PrimaryEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(8.dp)
                ) {
                    Column {
                        Text("Overlay", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (isOverlay) "Granted" else "Missing",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isOverlay) PrimaryEmerald else MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Battery Badge
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isBattery) PrimaryEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(8.dp)
                ) {
                    Column {
                        Text("Battery Saver", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (isBattery) "Ignored" else "Restricted",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isBattery) PrimaryEmerald else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onManagePermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manage_all_permissions_button"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Manage All Permissions & Auto-Start", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun MasterSwitchCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("master_switch_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-Accept Automation",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isEnabled) {
                        "Automated click triggers automatically when offer criteria are satisfied."
                    } else {
                        "Automation is paused. Screen requests will be evaluated and logged only."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = PrimaryEmerald
                ),
                modifier = Modifier.testTag("master_automation_switch")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionThresholdsCard(
    settings: SettingsState,
    onMinFareChanged: (Float) -> Unit,
    onMaxDistanceChanged: (Float) -> Unit,
    onDelayChanged: (Long) -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("decision_thresholds_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SurfaceStroke))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = PrimaryEmerald,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Acceptance Criteria",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- MINIMUM FARE SETTING ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Minimum Total Fare",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Base fare + tips (e.g. ₹56 + ₹13)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "₹${settings.minFare.roundToInt()}",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryEmerald
                    )
                )
            }

            Slider(
                value = settings.minFare,
                onValueChange = onMinFareChanged,
                valueRange = 20f..300f,
                steps = 27,
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryEmerald,
                    activeTrackColor = PrimaryEmerald
                ),
                modifier = Modifier.testTag("min_fare_slider")
            )

            // Quick Preset Chips for Fare
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val farePresets = listOf(40f, 60f, 80f, 100f, 150f, 200f)
                farePresets.forEach { preset ->
                    val isSelected = (settings.minFare.roundToInt() == preset.toInt())
                    FilterChip(
                        selected = isSelected,
                        onClick = { onMinFareChanged(preset) },
                        label = { Text("₹${preset.toInt()}", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryEmerald,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- MAXIMUM PICKUP DISTANCE ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Maximum Pickup Distance",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Ignore rides further than this",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${String.format(Locale.US, "%.1f", settings.maxPickupDistance)} km",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                )
            }

            Slider(
                value = settings.maxPickupDistance,
                onValueChange = onMaxDistanceChanged,
                valueRange = 0.5f..10.0f,
                steps = 18,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.secondary,
                    activeTrackColor = MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.testTag("max_distance_slider")
            )

            // Quick Preset Chips for Distance
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val distancePresets = listOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 7.0f)
                distancePresets.forEach { preset ->
                    val isSelected = (String.format(Locale.US, "%.1f", settings.maxPickupDistance) == String.format(Locale.US, "%.1f", preset))
                    FilterChip(
                        selected = isSelected,
                        onClick = { onMaxDistanceChanged(preset) },
                        label = { Text("${preset}km", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- REACTION DELAY ---
            Text(
                text = "Auto-Click Reaction Delay: ${settings.clickDelayMs}ms",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                listOf(250L to "Fast (250ms)", 500L to "Safe (500ms)", 1000L to "1.0 sec", 1500L to "1.5 sec").forEach { (delay, label) ->
                    val isSelected = settings.clickDelayMs == delay
                    FilterChip(
                        selected = isSelected,
                        onClick = { onDelayChanged(delay) },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceAnnouncerCard(
    isEnabled: Boolean,
    selectedLanguage: String,
    onToggle: (Boolean) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onTestVoice: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_announcer_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SurfaceStroke))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isEnabled) PrimaryEmerald.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isEnabled) Icons.Default.RecordVoiceOver else Icons.Default.VoiceOverOff,
                            contentDescription = "Voice Announcer Status Icon",
                            tint = if (isEnabled) PrimaryEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Voice Announcer (TTS)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Speaks ride fare, pickup distance & status",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryEmerald
                    ),
                    modifier = Modifier.testTag("voice_announcer_switch")
                )
            }

            if (isEnabled) {
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Announcer Language",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedLanguage.equals("en", ignoreCase = true),
                        onClick = { onSelectLanguage("en") },
                        label = { Text("English (en-IN)", fontSize = 12.sp) },
                        leadingIcon = if (selectedLanguage.equals("en", ignoreCase = true)) {
                            { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryEmerald,
                            selectedLabelColor = Color.Black,
                            selectedLeadingIconColor = Color.Black
                        ),
                        modifier = Modifier.testTag("voice_lang_english_chip")
                    )

                    FilterChip(
                        selected = selectedLanguage.equals("hi", ignoreCase = true),
                        onClick = { onSelectLanguage("hi") },
                        label = { Text("Hindi (हिन्दी)", fontSize = 12.sp) },
                        leadingIcon = if (selectedLanguage.equals("hi", ignoreCase = true)) {
                            { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryEmerald,
                            selectedLabelColor = Color.Black,
                            selectedLeadingIconColor = Color.Black
                        ),
                        modifier = Modifier.testTag("voice_lang_hindi_chip")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onTestVoice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_voice_announcement_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedLanguage.equals("hi", ignoreCase = true)) {
                            "Test Speech (परीक्षण आवाज़ सुनें)"
                        } else {
                            "Test Voice Announcement"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideSimulatorCard(
    settings: SettingsState,
    onSimulateAccept: (String) -> Unit
) {
    var offerInput by remember {
        mutableStateOf("Rapido Ride: ₹65 + ₹15 • Pickup: 1.2 km • Drop: 5.8 km [Accept]")
    }

    val parsedOffer = remember(offerInput) {
        TextAnalysisEngine.parseRideOffer(offerInput)
    }

    val evaluation = remember(parsedOffer, settings) {
        TextAnalysisEngine.evaluateRideOffer(
            offer = parsedOffer,
            minFare = settings.minFare,
            maxPickupDistance = settings.maxPickupDistance,
            isAutoAcceptEnabled = settings.isAutoAcceptEnabled
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ride_simulator_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = PrimaryEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ride Request Sandbox",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Text(
                    text = "Live Test",
                    style = MaterialTheme.typography.labelSmall.copy(color = PrimaryEmerald, fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Test live string parsing and validation without waiting for an actual on-road ride request:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Presets
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val presets = listOf(
                    "Standard (₹80)" to "Rapido Ride: ₹65 + ₹15 • Pickup: 1.2 km • Drop: 5.8 km [Accept]",
                    "High Fare (₹118)" to "Captain Offer: ₹95 + ₹23 • Pickup 2.1 km • Drop 8.0 km [Accept]",
                    "Low Fare (₹35)" to "Order Offer: ₹35 • Pickup 0.8 km • Drop 2.0 km [Accept]",
                    "Far Pickup (6.5km)" to "Offer: ₹120 • Pickup 6.5 km • Drop 14.0 km [Accept]"
                )

                presets.forEach { (label, text) ->
                    OutlinedButton(
                        onClick = { offerInput = text },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = offerInput,
                onValueChange = { offerInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("simulator_offer_input"),
                label = { Text("Simulated Screen Text", fontSize = 12.sp) },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryEmerald
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Parsed parameter badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Total Fare Badge
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                ) {
                    Column {
                        Text("Parsed Fare", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = parsedOffer.totalFare?.let { "₹${it.toInt()}" } ?: "Not Found",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (parsedOffer.totalFare != null) PrimaryEmerald else MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Pickup Distance Badge
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                ) {
                    Column {
                        Text("Pickup Dist", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = parsedOffer.pickupDistanceKm?.let { "${it}km" } ?: "Not Found",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (parsedOffer.pickupDistanceKm != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Accept Button Badge
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                ) {
                    Column {
                        Text("Accept Node", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (parsedOffer.hasAcceptButton) "Detected" else "Missing",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (parsedOffer.hasAcceptButton) PrimaryEmerald else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Evaluation status strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (evaluation.isAccepted) PrimaryEmerald.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    )
                    .border(
                        1.dp,
                        if (evaluation.isAccepted) PrimaryEmerald else MaterialTheme.colorScheme.error,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (evaluation.isAccepted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (evaluation.isAccepted) PrimaryEmerald else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = evaluation.decisionReason,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Test Action button
            Button(
                onClick = { onSimulateAccept(offerInput) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("simulate_accept_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (evaluation.isAccepted) PrimaryEmerald else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (evaluation.isAccepted) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (evaluation.isAccepted) "Trigger Simulated Auto-Accept" else "Simulate Evaluation Event",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ActivityLogsSection(
    logs: List<ActivityLogEntry>,
    onClearLogs: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity_logs_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live Activity & Action Feed",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (logs.isNotEmpty()) {
                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier.size(32.dp).testTag("clear_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear logs",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No events logged yet. Screen activity and evaluations will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    logs.take(15).forEach { log ->
                        LogItemRow(log)
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemRow(log: ActivityLogEntry) {
    val (badgeBg, badgeText, badgeColor) = when (log.severity) {
        LogSeverity.MATCH_ACCEPTED -> Triple(PrimaryEmerald.copy(alpha = 0.2f), "MATCH", PrimaryEmerald)
        LogSeverity.CLICK_EXECUTED -> Triple(PrimaryEmerald, "ACCEPTED", Color.Black)
        LogSeverity.REJECTED -> Triple(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f), "REJECTED", MaterialTheme.colorScheme.secondary)
        LogSeverity.WARNING -> Triple(MaterialTheme.colorScheme.error.copy(alpha = 0.2f), "WARN", MaterialTheme.colorScheme.error)
        LogSeverity.INFO -> Triple(MaterialTheme.colorScheme.surfaceVariant, "INFO", MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = badgeColor
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = log.title,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = log.timeFormatted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
