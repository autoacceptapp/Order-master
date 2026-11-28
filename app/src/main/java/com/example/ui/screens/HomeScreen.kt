package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.widget.Toast
import kotlinx.coroutines.launch
import com.example.PassManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AccessStatus
import com.example.ActivityLogEntry
import com.example.AppSettings
import com.example.LicenseManager
import com.example.LogSeverity
import com.example.PermissionOemHealthChecklistCard
import com.example.PermissionUtils
import com.example.R
import com.example.TTSManager
import com.example.auth.GoogleAuthManager
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Lock
import com.example.ui.OrderMasterViewModel
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.components.AppTutorialGuideDialog
import com.example.ui.components.AppGuideBannerCard
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: OrderMasterViewModel,
    onNavigateToSettings: () -> Unit,
    onOpenRestrictedSettingsGuide: () -> Unit,
    onNavigateToFilters: () -> Unit = {},
    onTriggerGoogleSignIn: () -> Unit = {},
    onNavigateToSubscription: () -> Unit = {},
    onNavigateToPaymentVerification: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showTutorialDialog by remember { mutableStateOf(false) }
    val settingsState by viewModel.settingsState.collectAsState()
    val logs by viewModel.liveLogs.collectAsState()
    val ttsStatus by viewModel.ttsStatus.collectAsState()
    val userAuthState by GoogleAuthManager.userAuthState.collectAsState()
    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val unlockedFeatures by LicenseManager.unlockedFeatures.collectAsState()
    val removeAdsExpiryMs by LicenseManager.removeAdsExpiryMs.collectAsState()
    val isAdsRemoved = remember(accessStatus, unlockedFeatures, removeAdsExpiryMs) { LicenseManager.isAdsRemoved() }
    val pointsBalance by LicenseManager.pointsBalance.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var isServiceRunningInSystem by remember {
        mutableStateOf(AppSettings.isAccessibilityServiceEnabled(context))
    }
    var isOverlayEnabled by remember {
        mutableStateOf(PermissionUtils.canDrawOverlays(context))
    }
    var isBatteryUnrestricted by remember {
        mutableStateOf(PermissionUtils.isIgnoringBatteryOptimizations(context))
    }
    var awaitingAccessibilityReturn by remember { mutableStateOf(false) }

    // Live-refresh permission health on ON_RESUME and auto-surface Restricted Settings Guide
    // if Accessibility remains OFF after returning from system settings on Android 13/14+.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val accessibilityNow = AppSettings.isAccessibilityServiceEnabled(context)
                isServiceRunningInSystem = accessibilityNow
                isOverlayEnabled = PermissionUtils.canDrawOverlays(context)
                isBatteryUnrestricted = PermissionUtils.isIgnoringBatteryOptimizations(context)

                if (awaitingAccessibilityReturn) {
                    awaitingAccessibilityReturn = false
                    if (!accessibilityNow && PermissionUtils.isAndroid13OrHigher()) {
                        onOpenRestrictedSettingsGuide()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isAutomationActive = settingsState.isAutoAcceptEnabled && isServiceRunningInSystem

    // 3. Server-Side / Expiry Date Check on opening HomeScreen
    LaunchedEffect(Unit) {
        if (!LicenseManager.hasLoadedLicensing) {
            PassManager.verifyServerPassStatus(context)
        }
    }

    val launchAccessibilitySettingsWithGuideFallback: () -> Unit = {
        awaitingAccessibilityReturn = true
        PermissionUtils.openAccessibilitySettings(context)
    }

    val handleOpenAccessibilityService: () -> Unit = {
        if (!isServiceRunningInSystem) {
            launchAccessibilitySettingsWithGuideFallback()
        } else {
            viewModel.toggleMasterAutomation()
        }
    }

    // Pulse animation for active radar scanning
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val guidedSetupState = remember(isServiceRunningInSystem, settingsState, isAutomationActive) {
        computeGuidedSetupState(
            isAccessibilityEnabled = isServiceRunningInSystem,
            settingsState = settingsState,
            isAutomationActive = isAutomationActive
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =========================================================================================
        // COMPONENT 0: 3-STEP GUIDED SETUP BANNER (ALWAYS CLEAR NEXT ACTION)
        // =========================================================================================
        GuidedSetupBanner(
            state = guidedSetupState,
            onFixNow = {
                when (guidedSetupState.nextIncompleteStep) {
                    1 -> launchAccessibilitySettingsWithGuideFallback()
                    2 -> onNavigateToFilters()
                    3 -> handleOpenAccessibilityService()
                }
            }
        )

        // =========================================================================================
        // COMPONENT 1: MASTER TOGGLE SWITCH & ACCESSIBILITY STATUS
        // =========================================================================================
        MasterToggleCard(
            isAutomationActive = isAutomationActive,
            isServiceRunningInSystem = isServiceRunningInSystem,
            isAutoAcceptEnabled = settingsState.isAutoAcceptEnabled,
            pulseScale = pulseScale,
            onToggle = handleOpenAccessibilityService,
            onFixService = launchAccessibilitySettingsWithGuideFallback
        )

        // =========================================================================================
        // COMPONENT 1.1: COLLAPSIBLE 1-TAP PERMISSION & OEM HEALTH CHECKLIST
        // =========================================================================================
        var showAllPermissionsChecklist by remember { mutableStateOf(false) }
        val readyCoreCount = listOf(
            isServiceRunningInSystem,
            isOverlayEnabled,
            isBatteryUnrestricted
        ).count { it }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { showAllPermissionsChecklist = !showAllPermissionsChecklist }
                .testTag("home_permissions_expand_toggle")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = if (readyCoreCount == 3) PrimaryEmerald else AmberAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Sabhi 4 Permissions Dekhein",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (readyCoreCount == 3) PrimaryEmerald else AmberAccent).copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = if (readyCoreCount == 3) "Ready" else "$readyCoreCount/3",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (readyCoreCount == 3) PrimaryEmerald else AmberAccent
                            ),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Icon(
                        imageVector = if (showAllPermissionsChecklist) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showAllPermissionsChecklist) "Chhupayein" else "Sabhi 4 Permissions Dekhein",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        AnimatedVisibility(visible = showAllPermissionsChecklist) {
            PermissionOemHealthChecklistCard(
                isAccessibilityEnabled = isServiceRunningInSystem,
                isOverlayEnabled = isOverlayEnabled,
                isBatteryUnrestricted = isBatteryUnrestricted,
                onClickAccessibility = launchAccessibilitySettingsWithGuideFallback,
                onClickOverlay = { PermissionUtils.openOverlaySettings(context) },
                onClickBattery = { PermissionUtils.openBatteryOptimizationSettings(context) },
                onClickOemAutoStart = { PermissionUtils.openOemAutoStartSettings(context) },
                onOpenRestrictedGuide = onOpenRestrictedSettingsGuide,
                defaultExpanded = true
            )
        }

        // =========================================================================================
        // COMPONENT 1.2: INTERACTIVE APP & FILTER SETUP GUIDE BANNER
        // =========================================================================================
        AppGuideBannerCard(
            onOpenGuide = { showTutorialDialog = true }
        )

        if (showTutorialDialog) {
            AppTutorialGuideDialog(
                initialStep = 0,
                onDismiss = {
                    showTutorialDialog = false
                    AppSettings.setHomeTutorialCompleted(context, true)
                }
            )
        }

        // =========================================================================================
        // COMPONENT 1.5: GOOGLE ACCOUNT & CLOUD SYNC CARD
        // =========================================================================================
        GoogleAuthStatusCard(
            isLoggedIn = userAuthState.isLoggedIn,
            userName = userAuthState.userName,
            userEmail = userAuthState.userEmail,
            onTriggerSignIn = onTriggerGoogleSignIn
        )

        // =========================================================================================
        // COMPONENT 1.6: CAPTAIN PASS & WALLET STATUS
        // =========================================================================================
        LicenseStatusHomeCard(
            accessStatus = accessStatus,
            pointsBalance = pointsBalance,
            isAdsRemoved = isAdsRemoved,
            onOpenStore = onNavigateToSubscription
        )

        // =========================================================================================
        // COMPONENT 2: LIVE FEED / ACTIVE MONITOR SECTION
        // =========================================================================================
        LiveFeedSection(
            logs = logs,
            ttsStatus = ttsStatus,
            isAutomationActive = isAutomationActive,
            pulseScale = pulseScale,
            onClearLogs = { viewModel.clearLiveFeed() },
            onSimulateOffer = { viewModel.simulateRideOffer() },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 420.dp)
        )
    }
}

/**
 * Prominent, tactile Master Automation toggle card with real-time status indicators.
 */
@Composable
private fun MasterToggleCard(
    isAutomationActive: Boolean,
    isServiceRunningInSystem: Boolean,
    isAutoAcceptEnabled: Boolean,
    pulseScale: Float,
    onToggle: () -> Unit,
    onFixService: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val activeColor = Color(0xFF10B981) // Emerald Green
    val pausedColor = Color(0xFFF59E0B) // Amber
    val disabledColor = Color(0xFFEF4444) // Coral Red

    val cardBgColor by animateColorAsState(
        targetValue = when {
            !isServiceRunningInSystem -> if (isDark) Color(0xFF2B181A) else Color(0xFFFEF2F2)
            isAutoAcceptEnabled -> if (isDark) Color(0xFF102A22) else Color(0xFFECFDF5)
            else -> if (isDark) Color(0xFF2B2212) else Color(0xFFFFFBEB)
        },
        label = "cardBgColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            !isServiceRunningInSystem -> disabledColor.copy(alpha = 0.55f)
            isAutoAcceptEnabled -> activeColor.copy(alpha = 0.65f)
            else -> pausedColor.copy(alpha = 0.55f)
        },
        label = "borderColor"
    )

    val statusSpeech = when {
        !isServiceRunningInSystem -> "Abhi permission baaki hai. Neeche laal button dabakar Order Master ki permission ON karein."
        isAutoAcceptEnabled -> "Auto Accept chalu hai. Rapido par aane wali har ride aapke filters ke hisaab se check ho rahi hai."
        else -> "Auto Accept abhi band hai. Shuru karne ke liye hara button dabayein."
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("master_toggle_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(borderColor, borderColor.copy(alpha = 0.3f))),
            width = 2.dp
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Status Badge, Voice Button & Large Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Pulsing Status Dot
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .then(if (isAutomationActive) Modifier.scale(pulseScale) else Modifier)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !isServiceRunningInSystem -> disabledColor
                                    isAutoAcceptEnabled -> activeColor
                                    else -> pausedColor
                                }
                            )
                    )

                    Column {
                        Text(
                            text = "AUTO RIDE HELPER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = when {
                                !isServiceRunningInSystem -> "PERMISSION BAAKI HAI"
                                isAutoAcceptEnabled -> "CHALU HAI • Ride Check Ho Rahi Hai"
                                else -> "ABHI BAND HAI"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = when {
                                    !isServiceRunningInSystem -> disabledColor
                                    isAutoAcceptEnabled -> activeColor
                                    else -> pausedColor
                                }
                            )
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = { TTSManager.speakGuide(context, statusSpeech, flush = true) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("master_card_speak_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Sunke Samjho",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Tactile Master Switch
                    Switch(
                        checked = isAutoAcceptEnabled && isServiceRunningInSystem,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier.testTag("master_toggle_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = activeColor,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }

            // Description or Alert Banner
            if (!isServiceRunningInSystem) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = disabledColor.copy(alpha = 0.1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onFixService() }
                        .testTag("accessibility_alert_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = disabledColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "App ko screen padhne ki permission nahi mili hai. Chalu karne ke liye button dabayein.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                color = disabledColor
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isAutoAcceptEnabled)
                            "Rapido par aane wali har ride aapke rules se check ho rahi hai."
                        else
                            "Auto-Accept ruka hua hai. Shuru karne ke liye neeche button dabayein.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = (if (isAutomationActive) activeColor else pausedColor).copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isAutomationActive) Icons.Default.FlashOn else Icons.Default.PauseCircle,
                                contentDescription = null,
                                tint = if (isAutomationActive) activeColor else pausedColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isAutomationActive) "CHALU" else "BAND",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAutomationActive) activeColor else pausedColor
                                )
                            )
                        }
                    }
                }
            }

            // Large 52dp+ 1-Tap Action Button for effortless operation
            Button(
                onClick = {
                    if (!isServiceRunningInSystem) onFixService() else onToggle()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        !isServiceRunningInSystem -> disabledColor
                        isAutoAcceptEnabled -> MaterialTheme.colorScheme.surfaceVariant
                        else -> activeColor
                    },
                    contentColor = when {
                        !isServiceRunningInSystem -> Color.White
                        isAutoAcceptEnabled -> MaterialTheme.colorScheme.onSurface
                        else -> Color.Black
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("master_toggle_action_button")
            ) {
                Icon(
                    imageVector = when {
                        !isServiceRunningInSystem -> Icons.Default.Security
                        isAutoAcceptEnabled -> Icons.Default.PauseCircle
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        !isServiceRunningInSystem -> "Permission ON Karein"
                        isAutoAcceptEnabled -> "Band Karein"
                        else -> "CHALU KAREIN"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold)
                )
            }
        }
    }
}

@androidx.annotation.VisibleForTesting
internal data class GuidedSetupState(
    val step1PermissionDone: Boolean,
    val step2FilterDone: Boolean,
    val step3StartDone: Boolean,
    val nextIncompleteStep: Int
)

@androidx.annotation.VisibleForTesting
internal fun computeGuidedSetupState(
    isAccessibilityEnabled: Boolean,
    settingsState: com.example.SettingsState,
    isAutomationActive: Boolean
): GuidedSetupState {
    val step1Done = isAccessibilityEnabled
    val step2Done = AppSettings.detectActiveSmartMode(settingsState) != AppSettings.SmartMode.CUSTOM ||
        settingsState.isMinFareEnabled ||
        settingsState.isMaxFareEnabled ||
        settingsState.isMaxPickupDistanceEnabled ||
        settingsState.isMaxDropDistanceEnabled ||
        settingsState.isMinPerKmRateEnabled
    val step3Done = isAutomationActive
    val nextStep = when {
        !step1Done -> 1
        !step2Done -> 2
        !step3Done -> 3
        else -> 0
    }
    return GuidedSetupState(
        step1PermissionDone = step1Done,
        step2FilterDone = step2Done,
        step3StartDone = step3Done,
        nextIncompleteStep = nextStep
    )
}

@Composable
private fun GuidedSetupBanner(
    state: GuidedSetupState,
    onFixNow: () -> Unit
) {
    val allDone = state.nextIncompleteStep == 0
    val accentColor = if (allDone) PrimaryEmerald else AmberAccent

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (allDone) {
                PrimaryEmerald.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("guided_setup_banner")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (allDone) {
                            "✅ Sab Taiyar Hai! Rapido Chalu Karein"
                        } else {
                            "3 Aasan Step Me Shuru Karein"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = if (allDone) PrimaryEmerald else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (state.nextIncompleteStep) {
                            1 -> "Agla kaam: Step 1 — Permission On karein"
                            2 -> "Agla kaam: Step 2 — Filter Mode chunein"
                            3 -> "Agla kaam: Step 3 — Auto-Accept Start karein"
                            else -> "Har step complete hai. Aapki ride auto-check ho rahi hai."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!allDone) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onFixNow,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("guided_setup_fix_now_btn")
                    ) {
                        Text(
                            text = "Theek Karein",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GuidedStepChip(
                    stepNumber = 1,
                    label = "Permission On",
                    done = state.step1PermissionDone,
                    isActive = state.nextIncompleteStep == 1,
                    modifier = Modifier.weight(1f)
                )
                GuidedStepChip(
                    stepNumber = 2,
                    label = "Filter Mode",
                    done = state.step2FilterDone,
                    isActive = state.nextIncompleteStep == 2,
                    modifier = Modifier.weight(1f)
                )
                GuidedStepChip(
                    stepNumber = 3,
                    label = "Start",
                    done = state.step3StartDone,
                    isActive = state.nextIncompleteStep == 3,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GuidedStepChip(
    stepNumber: Int,
    label: String,
    done: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val color = when {
        done -> PrimaryEmerald
        isActive -> AmberAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = if (done || isActive) 0.14f else 0.06f),
        border = BorderStroke(1.dp, color.copy(alpha = if (isActive) 0.7f else 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "$stepNumber. $label",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (done || isActive) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 10.sp
                ),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Real-time logging card and list showing live events, detected overlay nodes,
 * parsed ride details, decision results, and active TTS status.
 */
@Composable
private fun LiveFeedSection(
    logs: List<ActivityLogEntry>,
    ttsStatus: String,
    isAutomationActive: Boolean,
    pulseScale: Float,
    onClearLogs: () -> Unit,
    onSimulateOffer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_feed_section"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Section Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Sensors,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Live Ride Check",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // TTS Status Chip
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = ttsStatus,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            )
                        }
                    }

                    // Clear Logs Button
                    if (logs.isNotEmpty()) {
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ClearAll,
                                contentDescription = "Clear Feed",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Feed Content: List vs Empty State
            if (logs.isEmpty()) {
                EmptyMonitorDisplay(
                    isAutomationActive = isAutomationActive,
                    pulseScale = pulseScale,
                    onSimulateOffer = onSimulateOffer,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("logs_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        LiveEventItem(log = log)
                    }
                }

                // Sandbox shortcut bar (debug builds only)
                if (com.example.BuildConfig.DEBUG) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onSimulateOffer,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Demo Ride Test Karein",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean, minimalistic empty-state radar scanner display when waiting for incoming ride offers.
 */
@Composable
private fun EmptyMonitorDisplay(
    isAutomationActive: Boolean,
    pulseScale: Float,
    onSimulateOffer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            // Radar Icon with gentle pulse
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .scale(if (isAutomationActive) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(
                        if (isAutomationActive)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = if (isAutomationActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = if (isAutomationActive)
                    "Nayi ride ka intezar hai..."
                else
                    "Auto-Accept Abhi Band Hai",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            Text(
                text = if (isAutomationActive)
                    "Rapido par ride aate hi uska kiraya aur doori yahan live dikhegi."
                else
                    "Ride check shuru karne ke liye upar wala bada button ON karein.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (com.example.BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(4.dp))

                FilledTonalButton(
                    onClick = onSimulateOffer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ElectricBolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Demo Ride Test Karein", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Individual live event item in the feed.
 */
@Composable
private fun LiveEventItem(log: ActivityLogEntry) {
    val isAccepted = log.severity == LogSeverity.MATCH_ACCEPTED || log.severity == LogSeverity.CLICK_EXECUTED
    val isRejected = log.severity == LogSeverity.REJECTED

    val badgeColor = when {
        isAccepted -> Color(0xFF10B981)
        isRejected -> Color(0xFFEF4444)
        log.severity == LogSeverity.WARNING -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row: Time + Title + Result Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = log.timeFormatted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Text(
                        text = log.title,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                if (isAccepted || isRejected) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = if (isAccepted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = badgeColor,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = if (isAccepted) "LI GAYI" else "CHHODI GAYI",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp,
                                    color = badgeColor
                                )
                            )
                        }
                    }
                }
            }

            // Message text
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            // Optional parsed details chip row if evaluation is attached
            log.evaluation?.let { eval ->
                val offer = eval.offer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    offer.totalFare?.let { fare ->
                        EvaluationChip(label = "Kiraya", value = "₹${fare.toInt()}")
                    }
                    offer.pickupDistanceKm?.let { dist ->
                        EvaluationChip(label = "Pickup", value = "${String.format(Locale.US, "%.1f", dist)}km")
                    }
                    offer.dropDistanceKm?.let { dist ->
                        EvaluationChip(label = "Drop", value = "${String.format(Locale.US, "%.1f", dist)}km")
                    }
                }
            }
        }
    }
}

@Composable
private fun EvaluationChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun GoogleAuthStatusCard(
    isLoggedIn: Boolean,
    userName: String?,
    userEmail: String?,
    onTriggerSignIn: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("google_auth_status_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoggedIn) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                if (isLoggedIn) {
                    listOf(Color(0xFF10B981).copy(alpha = 0.5f), Color(0xFF10B981).copy(alpha = 0.15f))
                } else {
                    listOf(
                        Color(0xFF4285F4).copy(alpha = 0.4f),
                        Color(0xFF34A853).copy(alpha = 0.3f),
                        Color(0xFFFBBC05).copy(alpha = 0.3f),
                        Color(0xFFEA4335).copy(alpha = 0.3f)
                    )
                }
            ),
            width = 1.5.dp
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isLoggedIn) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userName ?: "Google Account Link Hai",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = userEmail ?: "Aapka data safe sync me hai",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.size(38.dp),
                        shadowElevation = 1.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_google_logo),
                                contentDescription = "Google Logo",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Google Account Jodein",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Apne filters aur points safe rakhein",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!isLoggedIn) {
                FilledTonalButton(
                    onClick = onTriggerSignIn,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("home_google_sign_in_button")
                ) {
                    Text(
                        text = "Sign In",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "Link Hai",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = PrimaryEmerald
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact, interactive card displaying the Captain's Active Pass / Trial countdown and Points Wallet.
 */
@Composable
private fun LicenseStatusHomeCard(
    accessStatus: AccessStatus,
    pointsBalance: Int,
    isAdsRemoved: Boolean = false,
    onOpenStore: () -> Unit
) {
    val currentAccess = accessStatus

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_license_status_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    when (currentAccess) {
                        is AccessStatus.PassActive -> PrimaryEmerald.copy(alpha = 0.6f)
                        is AccessStatus.TrialActive -> Color(0xFF0284C7).copy(alpha = 0.6f)
                        else -> AmberAccent.copy(alpha = 0.55f)
                    },
                    AmberAccent.copy(alpha = 0.3f)
                )
            ),
            width = 1.5.dp
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = when (currentAccess) {
                        is AccessStatus.PassActive -> PrimaryEmerald.copy(alpha = 0.15f)
                        is AccessStatus.TrialActive -> Color(0xFF0284C7).copy(alpha = 0.15f)
                        else -> AmberAccent.copy(alpha = 0.18f)
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (currentAccess) {
                                is AccessStatus.PassActive -> Icons.Default.CardGiftcard
                                is AccessStatus.TrialActive -> Icons.Default.FlashOn
                                is AccessStatus.Expired -> Icons.Default.Lock
                                AccessStatus.Loading -> Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            tint = when (currentAccess) {
                                is AccessStatus.PassActive -> PrimaryEmerald
                                is AccessStatus.TrialActive -> Color(0xFF0284C7)
                                is AccessStatus.Expired -> AmberAccent
                                AccessStatus.Loading -> PrimaryEmerald
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (currentAccess) {
                                is AccessStatus.PassActive -> currentAccess.passTier.title
                                is AccessStatus.TrialActive -> "2-Din Free Trial"
                                is AccessStatus.Expired -> "Free Mode Chalu"
                                AccessStatus.Loading -> "Check ho raha hai..."
                            },
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• $pointsBalance Pts",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = AmberAccent
                            )
                        )
                        if (isAdsRemoved) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF8B5CF6).copy(alpha = 0.18f)
                            ) {
                                Text(
                                    text = "AD FREE",
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 9.sp,
                                        color = Color(0xFF8B5CF6)
                                    ),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = when (currentAccess) {
                            is AccessStatus.PassActive -> "Samay baaki: ${currentAccess.formattedRemaining}"
                            is AccessStatus.TrialActive -> "Trial baaki: ${currentAccess.formattedRemaining}"
                            is AccessStatus.Expired -> "Points se filter kholein ya Pass lein"
                            AccessStatus.Loading -> "Account check ho raha hai..."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (currentAccess !is AccessStatus.Loading) {
                Button(
                    onClick = onOpenStore,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (currentAccess) {
                            is AccessStatus.PassActive -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when (currentAccess) {
                            is AccessStatus.PassActive -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("home_store_pass_button")
                ) {
                    Text(
                        text = if (currentAccess is AccessStatus.PassActive) "Dekhein" else "Store",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

