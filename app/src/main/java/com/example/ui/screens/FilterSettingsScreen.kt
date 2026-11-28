package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import com.example.PassTier
import com.example.SUPPORTED_LANGUAGES
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.AppSettings
import com.example.PermissionUtils
import com.example.LicenseManager
import com.example.AccessStatus
import com.example.FeatureIds
import com.example.FeatureUnlockDialog
import com.example.MultiFilterUnlockDialog
import com.example.ui.OrderMasterViewModel
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.example.ui.components.FareFilterCard
import com.example.ui.components.AppTutorialGuideDialog
import com.example.ui.components.AppGuideBannerCard
import com.example.ui.theme.PrimaryEmerald
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSettingsScreen(
    viewModel: OrderMasterViewModel,
    onNavigateToSubscription: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showTutorialDialog by remember { mutableStateOf(false) }
    val settingsState by viewModel.settingsState.collectAsState()
    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val unlockedFeatures by LicenseManager.unlockedFeatures.collectAsState()
    val lockedFeatures by LicenseManager.lockedFeatures.collectAsState()
    val pointsBalance by LicenseManager.pointsBalance.collectAsState()
    val paymentConfig by com.example.data.repository.AppConfigRepository.currentPaymentConfig.collectAsState()

    val minFareRate = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.MIN_FARE) }
    val maxFareRate = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.MAX_FARE) }
    val maxPickupRate = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.MAX_PICKUP_DISTANCE) }
    val maxDropRate = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.MAX_DROP_DISTANCE) }
    val minPerKmRatePts = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.MIN_PER_KM_RATE) }
    val blacklistRate = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.DESTINATION_BLACKLIST) }
    val ttsRate = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.TTS_VOICE) }
    val overlayRate = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.FLOATING_OVERLAY) }
    val autoClickRate = remember(paymentConfig) { FeatureIds.getEffectivePointsPerHour(FeatureIds.AUTO_CLICK) }

    val isProMode = when (val s = accessStatus) {
        is AccessStatus.PassActive -> s.passTier.isProPass && s.remainingTimeMs > 0
        is AccessStatus.TrialActive -> s.remainingTimeMs > 0
        else -> false
    }

    var featureToUnlock by remember { mutableStateOf<String?>(null) }
    var showMultiFilterDialog by remember { mutableStateOf(false) }
    var multiFilterPreselectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun getRemainingTime(fId: String): String? {
        if (LicenseManager.isFeatureLockedByAdmin(fId)) return null
        if (!LicenseManager.isFeatureUnlocked(fId)) return null
        val ms = LicenseManager.getFeatureRemainingTimeMs(fId)
        if (ms <= 0L) return null
        if (ms == Long.MAX_VALUE) return "ADMIN UNLOCKED"
        if (isProMode) return "PRO ACTIVE"
        val totalMins = (ms / 60000L).coerceAtLeast(1L)
        val hrs = totalMins / 60
        val mins = totalMins % 60
        return if (hrs > 0) "${hrs}h ${mins}m left" else "${mins}m left"
    }

    val isMinFareAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.MIN_FARE) }
    val isMinFareUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE) }

    val isMaxFareAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.MAX_FARE) }
    val isMaxFareUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.MAX_FARE) }

    val isMinRateAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.MIN_PER_KM_RATE) }
    val isMinRateUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.MIN_PER_KM_RATE) }

    val isPickupAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.MAX_PICKUP_DISTANCE) }
    val isPickupUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE) }

    val isDropAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.MAX_DROP_DISTANCE) }
    val isDropUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.MAX_DROP_DISTANCE) }

    val isBlacklistAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.DESTINATION_BLACKLIST) }
    val isBlacklistUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.DESTINATION_BLACKLIST) }

    val isTtsAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.TTS_VOICE) }
    val isTtsUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE) }

    val isOverlayAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.FLOATING_OVERLAY) }
    val isOverlayUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.FLOATING_OVERLAY) }

    val isAutoClickAdminLocked = remember(lockedFeatures) { LicenseManager.isFeatureLockedByAdmin(FeatureIds.AUTO_CLICK) }
    val isAutoClickUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) { LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK) }
    val hasAnyAutoCapability = remember(accessStatus, unlockedFeatures, lockedFeatures) {
        LicenseManager.hasAnyAutoAcceptCapability()
    }
    val hasAnyEvaluationFilterUnlocked = isMinFareUnlocked || isMaxFareUnlocked ||
        isMinRateUnlocked || isPickupUnlocked || isDropUnlocked || isBlacklistUnlocked
    val hasAllFiltersForPresets = isProMode || (
        isMinFareUnlocked && isMaxFareUnlocked && isPickupUnlocked && isDropUnlocked && isMinRateUnlocked
    )

    var languageDropdownExpanded by remember { mutableStateOf(false) }
    var newBlacklistKeyword by remember { mutableStateOf("") }

    var hasOverlayPermission by remember {
        mutableStateOf(PermissionUtils.canDrawOverlays(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = PermissionUtils.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // Feature Unlock Micro-transaction Dialog (Single)
    featureToUnlock?.let { fId ->
        FeatureUnlockDialog(
            featureId = fId,
            onDismiss = { featureToUnlock = null },
            onSuccess = { featureToUnlock = null },
            onOpenMultiFilter = {
                featureToUnlock = null
                multiFilterPreselectedIds = setOf(fId)
                showMultiFilterDialog = true
            },
            onOpenPassStore = {
                featureToUnlock = null
                onNavigateToSubscription()
            }
        )
    }

    // Multi-Select Filter Purchase Dialog (Free Mode)
    if (showMultiFilterDialog) {
        MultiFilterUnlockDialog(
            initiallySelectedIds = multiFilterPreselectedIds,
            onDismiss = {
                showMultiFilterDialog = false
                multiFilterPreselectedIds = emptySet()
            },
            onSuccess = {
                showMultiFilterDialog = false
                multiFilterPreselectedIds = emptySet()
            },
            onOpenPassStore = {
                showMultiFilterDialog = false
                multiFilterPreselectedIds = emptySet()
                onNavigateToSubscription()
            }
        )
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = PermissionUtils.canDrawOverlays(context)
        hasOverlayPermission = granted
        if (granted) {
            viewModel.toggleFloatingOverlay(true)
        } else {
            viewModel.toggleFloatingOverlay(false)
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Overlay Permission Required",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = "To show the floating button over driver applications (e.g., Rapido Captain), Order Master requires the 'Display over other apps' system permission.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTutorialDialog) {
        AppTutorialGuideDialog(
            onDismiss = { showTutorialDialog = false },
            initialStep = 1
        )
    }

    val currentLang = settingsState.voiceLanguage.trim().lowercase(Locale.ROOT)
    val filterCopy = remember(currentLang) { getLocalizedFilterCopy(currentLang) }
    val activeSmartMode = remember(settingsState) { AppSettings.detectActiveSmartMode(settingsState) }
    val plainSummaryText = remember(settingsState, currentLang) {
        AppSettings.buildPlainLanguageFilterSummary(settingsState, currentLang)
    }

    val handleSmartModeClick: (AppSettings.SmartMode) -> Unit = { mode ->
        if (mode == AppSettings.SmartMode.CUSTOM) {
            Toast.makeText(context, filterCopy.customHintToast, Toast.LENGTH_SHORT).show()
        } else {
            val result = viewModel.applySmartMode(mode)
            if (result.appliedCount == 0 && result.missingFeatureIds.isNotEmpty()) {
                val unlockable = result.missingFeatureIds.filterNot { LicenseManager.isFeatureLockedByAdmin(it) }
                if (unlockable.isNotEmpty()) {
                    multiFilterPreselectedIds = unlockable.toSet()
                    showMultiFilterDialog = true
                } else {
                    Toast.makeText(context, "Filters are locked by Admin", Toast.LENGTH_SHORT).show()
                }
            } else {
                val modeName = if (mode == AppSettings.SmartMode.SAFE) filterCopy.safeModeLabel else filterCopy.zyadaKamaiLabel
                val suffix = if (result.missingFeatureIds.isNotEmpty()) {
                    " (${result.missingFeatureIds.size} locked)"
                } else {
                    ""
                }
                Toast.makeText(context, "$modeName ON!$suffix", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // =========================================================================================
        // STICKY TOP BAR: 1-TAP SMART MODES (Safe Mode, Zyada Kamai, Lamba Route, Reset)
        // =========================================================================================
        SmartModesStickyBar(
            activeMode = activeSmartMode,
            safeModeLabel = filterCopy.safeModeLabel,
            zyadaKamaiLabel = filterCopy.zyadaKamaiLabel,
            customLabel = filterCopy.customLabel,
            headerLabel = filterCopy.smartModesHeader,
            onSelectMode = handleSmartModeClick,
            onApplyLongRoute = {
                val applied = viewModel.applyPreset("high")
                if (applied) {
                    Toast.makeText(context, "Lamba Route Mode ON (₹150+ • ₹15/km)!", Toast.LENGTH_SHORT).show()
                }
            },
            onResetDefaults = {
                viewModel.resetToSafeDefaults()
                Toast.makeText(context, filterCopy.resetSuccessToast, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("filter_settings_list"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Live Plain-Language "Iska Matlab Kya Hai?" Summary Card
            item {
                PlainLanguageSummaryCard(
                    heading = filterCopy.summaryHeading,
                    summaryText = plainSummaryText,
                    onSpeak = { viewModel.speakFilterExplanation(plainSummaryText) },
                    speakLabel = filterCopy.listenButtonLabel
                )
            }

            // Interactive Tutorial & Filter Training Guide Banner
            item {
                AppGuideBannerCard(
                    onOpenGuide = { showTutorialDialog = true }
                )
            }

            // Operating Mode Status Banner
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isProMode) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                    },
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (isProMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                        )
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("operating_mode_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isProMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isProMode) Icons.Default.CheckCircle else Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (isProMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isProMode) "PRO PASS CHALU HAI" else "FREE MODE CHALU",
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isProMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                    )
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ) {
                                    Text(
                                        text = "$pointsBalance Pts Balance",
                                        maxLines = 1,
                                        softWrap = false,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            val nowServerMs = LicenseManager.getCurrentServerTimeMs()
                            val unlockedFreeCount = unlockedFeatures.count { (k, v) ->
                                !k.equals(PassTier.REMOVE_ADS.id, ignoreCase = true) &&
                                    !LicenseManager.isFeatureLockedByAdmin(k) &&
                                    (v == -1L || v > nowServerMs)
                            }
                            Text(
                                text = when {
                                    isProMode -> "Sabhi unlocked filter rules chalu hain — tez auto-accept ke saath."
                                    unlockedFreeCount > 0 -> "Free Mode: Points se $unlockedFreeCount filter khula hua hai."
                                    else -> "Free Mode me filters lock hain. Points se filter kholein ya Pro Pass lein."
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            )
                            if (!isProMode) {
                                Spacer(modifier = Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = {
                                        multiFilterPreselectedIds = emptySet()
                                        showMultiFilterDialog = true
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("buy_multiple_filters_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Points Se Filter Kholein",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================================
            // 1. MINIMUM FARE FILTER (FareFilterCard)
            // =========================================================================================
            item {
                val minExceedsMax = settingsState.isMinFareEnabled &&
                        settingsState.isMaxFareEnabled &&
                        settingsState.minFare > settingsState.maxFare

                FareFilterCard(
                    title = filterCopy.minFareTitle,
                    description = filterCopy.minFareDesc,
                    exampleText = filterCopy.minFareExample,
                    onSpeakExplain = { viewModel.speakFilterExplanation(filterCopy.minFareSpeech) },
                    speakContentDescription = filterCopy.listenButtonLabel,
                    icon = Icons.Default.CurrencyRupee,
                    isEnabled = settingsState.isMinFareEnabled,
                    onToggle = { if (isMinFareUnlocked) viewModel.toggleMinFareFilter(it) },
                    value = settingsState.minFare,
                    onValueChange = { if (isMinFareUnlocked) viewModel.setMinFare(it) },
                    valueRange = AppSettings.MIN_FARE_RANGE_START..AppSettings.MIN_FARE_RANGE_END,
                    presetValues = listOf(40f, 60f, 80f, 100f, 150f),
                    accentColor = MaterialTheme.colorScheme.primary,
                    validationWarning = if (minExceedsMax) "Kam kiraya Zyada kiraye (₹${settingsState.maxFare.toInt()}) se bada nahi ho sakta" else null,
                    testTag = "min_fare_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isMinFareUnlocked,
                    isAdminLocked = isMinFareAdminLocked,
                    featureId = FeatureIds.MIN_FARE,
                    onUnlockClick = { if (!isMinFareAdminLocked) featureToUnlock = FeatureIds.MIN_FARE },
                    remainingTimeText = getRemainingTime(FeatureIds.MIN_FARE),
                    pointsPerHour = minFareRate
                )
            }

            // =========================================================================================
            // 2. MAXIMUM FARE FILTER (FareFilterCard)
            // =========================================================================================
            item {
                val minExceedsMax = settingsState.isMinFareEnabled &&
                        settingsState.isMaxFareEnabled &&
                        settingsState.minFare > settingsState.maxFare

                FareFilterCard(
                    title = filterCopy.maxFareTitle,
                    description = filterCopy.maxFareDesc,
                    exampleText = filterCopy.maxFareExample,
                    onSpeakExplain = { viewModel.speakFilterExplanation(filterCopy.maxFareSpeech) },
                    speakContentDescription = filterCopy.listenButtonLabel,
                    icon = Icons.Default.CurrencyRupee,
                    isEnabled = settingsState.isMaxFareEnabled,
                    onToggle = { if (isMaxFareUnlocked) viewModel.toggleMaxFareFilter(it) },
                    value = settingsState.maxFare,
                    onValueChange = { if (isMaxFareUnlocked) viewModel.setMaxFare(it) },
                    valueRange = AppSettings.MAX_FARE_RANGE_START..AppSettings.MAX_FARE_RANGE_END,
                    presetValues = listOf(200f, 300f, 500f, 800f, 1000f),
                    accentColor = MaterialTheme.colorScheme.primary,
                    secondaryBadgeColor = MaterialTheme.colorScheme.secondaryContainer,
                    validationWarning = if (minExceedsMax) "Zyada kiraya Kam kiraye (₹${settingsState.minFare.toInt()}) se kam nahi ho sakta" else null,
                    testTag = "max_fare_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isMaxFareUnlocked,
                    isAdminLocked = isMaxFareAdminLocked,
                    featureId = FeatureIds.MAX_FARE,
                    onUnlockClick = { if (!isMaxFareAdminLocked) featureToUnlock = FeatureIds.MAX_FARE },
                    remainingTimeText = getRemainingTime(FeatureIds.MAX_FARE),
                    pointsPerHour = maxFareRate
                )
            }

            // =========================================================================================
            // 3. MAXIMUM PICKUP DISTANCE FILTER (0.0 km to 15.0 km)
            // =========================================================================================
            item {
                FilterCard(
                    title = filterCopy.pickupTitle,
                    subtitle = filterCopy.pickupDesc,
                    exampleText = filterCopy.pickupExample,
                    onSpeakExplain = { viewModel.speakFilterExplanation(filterCopy.pickupSpeech) },
                    speakContentDescription = filterCopy.listenButtonLabel,
                    icon = Icons.Default.NearMe,
                    isEnabled = settingsState.isMaxPickupDistanceEnabled,
                    onToggle = { if (isPickupUnlocked) viewModel.toggleMaxPickupDistFilter(it) },
                    testTag = "pickup_dist_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isPickupUnlocked,
                    isAdminLocked = isPickupAdminLocked,
                    featureId = FeatureIds.MAX_PICKUP_DISTANCE,
                    onUnlockClick = { if (!isPickupAdminLocked) featureToUnlock = FeatureIds.MAX_PICKUP_DISTANCE },
                    remainingTimeText = getRemainingTime(FeatureIds.MAX_PICKUP_DISTANCE),
                    pointsPerHour = maxPickupRate
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Grahak Ki Max Doori:",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", settingsState.maxPickupDistance)} km",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Slider(
                            value = settingsState.maxPickupDistance.coerceIn(
                                AppSettings.MIN_PICKUP_DISTANCE_KM,
                                AppSettings.MAX_PICKUP_DISTANCE_KM
                            ),
                            enabled = isPickupUnlocked,
                            onValueChange = { if (isPickupUnlocked) viewModel.setMaxPickupDistance((it * 10).roundToInt() / 10f) },
                            valueRange = AppSettings.MIN_PICKUP_DISTANCE_KM..AppSettings.MAX_PICKUP_DISTANCE_KM,
                            steps = 0, // Smooth 0.1km snapping up to 15.0 km
                            modifier = Modifier.fillMaxWidth().testTag("pickup_dist_slider")
                        )

                        // Quick Select Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(1.5f, 3.0f, 5.0f, 10.0f, 15.0f).forEach { distVal ->
                                val selected = kotlin.math.abs(settingsState.maxPickupDistance - distVal) < 0.05f
                                OutlinedButton(
                                    onClick = { if (isPickupUnlocked) viewModel.setMaxPickupDistance(distVal) },
                                    enabled = isPickupUnlocked,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = if (selected) ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ) else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(
                                        text = "${distVal}km",
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================================
            // 4. MAXIMUM DROP DISTANCE FILTER
            // =========================================================================================
            item {
                FilterCard(
                    title = filterCopy.dropTitle,
                    subtitle = filterCopy.dropDesc,
                    exampleText = filterCopy.dropExample,
                    onSpeakExplain = { viewModel.speakFilterExplanation(filterCopy.dropSpeech) },
                    speakContentDescription = filterCopy.listenButtonLabel,
                    icon = Icons.Default.DirectionsCar,
                    isEnabled = settingsState.isMaxDropDistanceEnabled,
                    onToggle = { if (isDropUnlocked) viewModel.toggleMaxDropDistFilter(it) },
                    testTag = "drop_dist_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isDropUnlocked,
                    isAdminLocked = isDropAdminLocked,
                    featureId = FeatureIds.MAX_DROP_DISTANCE,
                    onUnlockClick = { if (!isDropAdminLocked) featureToUnlock = FeatureIds.MAX_DROP_DISTANCE },
                    remainingTimeText = getRemainingTime(FeatureIds.MAX_DROP_DISTANCE),
                    pointsPerHour = maxDropRate
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Trip Ki Max Doori:",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", settingsState.maxDropDistance)} km",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Slider(
                            value = settingsState.maxDropDistance.coerceIn(1.0f, 50.0f),
                            enabled = isDropUnlocked,
                            onValueChange = { if (isDropUnlocked) viewModel.setMaxDropDistance((it * 2).roundToInt() / 2f) },
                            valueRange = 1.0f..50.0f,
                            steps = 97, // 0.5km steps
                            modifier = Modifier.fillMaxWidth().testTag("drop_dist_slider")
                        )

                        // Quick Select Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(5.0f, 10.0f, 15.0f, 25.0f, 50.0f).forEach { distVal ->
                                val selected = kotlin.math.abs(settingsState.maxDropDistance - distVal) < 0.25f
                                OutlinedButton(
                                    onClick = { if (isDropUnlocked) viewModel.setMaxDropDistance(distVal) },
                                    enabled = isDropUnlocked,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = if (selected) ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ) else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(
                                        text = "${distVal.toInt()}km",
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================================
            // 5. MINIMUM RATE PER-KM FILTER (₹/km)
            // =========================================================================================
            item {
                FilterCard(
                    title = filterCopy.minRateTitle,
                    subtitle = filterCopy.minRateDesc,
                    exampleText = filterCopy.minRateExample,
                    onSpeakExplain = { viewModel.speakFilterExplanation(filterCopy.minRateSpeech) },
                    speakContentDescription = filterCopy.listenButtonLabel,
                    icon = Icons.Default.Speed,
                    isEnabled = settingsState.isMinPerKmRateEnabled,
                    onToggle = { if (isMinRateUnlocked) viewModel.toggleMinPerKmRate(it) },
                    testTag = "min_per_km_rate_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isMinRateUnlocked,
                    isAdminLocked = isMinRateAdminLocked,
                    featureId = FeatureIds.MIN_PER_KM_RATE,
                    onUnlockClick = { if (!isMinRateAdminLocked) featureToUnlock = FeatureIds.MIN_PER_KM_RATE },
                    remainingTimeText = getRemainingTime(FeatureIds.MIN_PER_KM_RATE),
                    pointsPerHour = minPerKmRatePts
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Har KM Kam Se Kam Rate:",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "₹${settingsState.minPerKmRate.toInt()}/km",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Slider(
                            value = settingsState.minPerKmRate,
                            enabled = isMinRateUnlocked,
                            onValueChange = { if (isMinRateUnlocked) viewModel.setMinPerKmRate(it.roundToInt().toFloat()) },
                            valueRange = 5.0f..30.0f,
                            steps = 24, // 1 ₹/km steps
                            modifier = Modifier.fillMaxWidth().testTag("min_per_km_slider")
                        )

                        // Quick Select Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(8f, 10f, 12f, 15f, 20f).forEach { rateVal ->
                                val selected = settingsState.minPerKmRate.roundToInt() == rateVal.toInt()
                                OutlinedButton(
                                    onClick = { if (isMinRateUnlocked) viewModel.setMinPerKmRate(rateVal) },
                                    enabled = isMinRateUnlocked,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = if (selected) ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ) else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(
                                        text = "₹${rateVal.toInt()}",
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================================
            // 6. DESTINATION KEYWORD BLACKLIST (Geofencing via text)
            // =========================================================================================
            item {
                FilterCard(
                    title = filterCopy.blacklistTitle,
                    subtitle = filterCopy.blacklistDesc,
                    exampleText = filterCopy.blacklistExample,
                    onSpeakExplain = { viewModel.speakFilterExplanation(filterCopy.blacklistSpeech) },
                    speakContentDescription = filterCopy.listenButtonLabel,
                    icon = Icons.Default.LocationOff,
                    isEnabled = settingsState.isDestinationBlacklistEnabled,
                    onToggle = { if (isBlacklistUnlocked) viewModel.toggleDestinationBlacklist(it) },
                    testTag = "destination_blacklist_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isBlacklistUnlocked,
                    isAdminLocked = isBlacklistAdminLocked,
                    featureId = FeatureIds.DESTINATION_BLACKLIST,
                    onUnlockClick = { if (!isBlacklistAdminLocked) featureToUnlock = FeatureIds.DESTINATION_BLACKLIST },
                    remainingTimeText = getRemainingTime(FeatureIds.DESTINATION_BLACKLIST),
                    pointsPerHour = blacklistRate
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newBlacklistKeyword,
                                onValueChange = { if (isBlacklistUnlocked) newBlacklistKeyword = it },
                                enabled = isBlacklistUnlocked,
                                placeholder = { Text("Jaise: Airport, Highway, Toll", fontSize = 13.sp) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("blacklist_keyword_input"),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            FilledTonalButton(
                                onClick = {
                                    if (isBlacklistUnlocked && newBlacklistKeyword.isNotBlank()) {
                                        viewModel.addBlockedLocation(newBlacklistKeyword)
                                        newBlacklistKeyword = ""
                                    }
                                },
                                enabled = isBlacklistUnlocked && newBlacklistKeyword.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("add_blacklist_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Keyword",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Jodein", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (settingsState.blockedLocations.isEmpty()) {
                            Text(
                                text = "Abhi koi jagah block nahi hai. Jahan nahi jana ho uska naam upar likhkar Jodein dabayein.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                settingsState.blockedLocations.forEach { keyword ->
                                    InputChip(
                                        selected = false,
                                        enabled = isBlacklistUnlocked,
                                        onClick = { if (isBlacklistUnlocked) viewModel.removeBlockedLocation(keyword) },
                                        label = { Text(keyword, fontSize = 12.sp) },
                                        trailingIcon = {
                                            if (isBlacklistUnlocked) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove $keyword",
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        },
                                        colors = InputChipDefaults.inputChipColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================================
            // 7. VOICE ANNOUNCEMENTS (TTS) FILTER
            // =========================================================================================
            item {
                FilterCard(
                    title = filterCopy.voiceTitle,
                    subtitle = filterCopy.voiceDesc,
                    exampleText = filterCopy.voiceExample,
                    onSpeakExplain = { viewModel.speakFilterExplanation(filterCopy.voiceSpeech) },
                    speakContentDescription = filterCopy.listenButtonLabel,
                    icon = Icons.Default.RecordVoiceOver,
                    isEnabled = settingsState.isVoiceAnnouncerEnabled,
                    onToggle = { if (isTtsUnlocked) viewModel.toggleVoiceAnnouncer(it) },
                    testTag = "voice_tts_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isTtsUnlocked,
                    isAdminLocked = isTtsAdminLocked,
                    featureId = FeatureIds.TTS_VOICE,
                    onUnlockClick = { if (!isTtsAdminLocked) featureToUnlock = FeatureIds.TTS_VOICE },
                    remainingTimeText = getRemainingTime(FeatureIds.TTS_VOICE),
                    pointsPerHour = ttsRate
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Bhasha Chunein (Voice Language):",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        )

                        // Language Selection Dropdown (supports Hinglish + all 10 regional languages)
                        val currentLangDisplay = remember(settingsState.voiceLanguage) {
                            if (settingsState.voiceLanguage.equals("hinglish", ignoreCase = true)) {
                                "Hinglish (Aasan Hindi + English)"
                            } else {
                                val obj = SUPPORTED_LANGUAGES.find { it.code.equals(settingsState.voiceLanguage, ignoreCase = true) } ?: SUPPORTED_LANGUAGES.first()
                                "${obj.nativeName} (${obj.englishName})"
                            }
                        }
                        ExposedDropdownMenuBox(
                            expanded = languageDropdownExpanded && isTtsUnlocked,
                            onExpandedChange = { if (isTtsUnlocked) languageDropdownExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = currentLangDisplay,
                                onValueChange = {},
                                readOnly = true,
                                enabled = isTtsUnlocked,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageDropdownExpanded && isTtsUnlocked) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = languageDropdownExpanded && isTtsUnlocked,
                                onDismissRequest = { languageDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Hinglish (Aasan Hindi + English)") },
                                    onClick = {
                                        viewModel.setVoiceLanguage("hinglish")
                                        languageDropdownExpanded = false
                                    }
                                )
                                SUPPORTED_LANGUAGES.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text("${lang.nativeName} (${lang.englishName})") },
                                        onClick = {
                                            viewModel.setVoiceLanguage(lang.code)
                                            languageDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Test Audio Button
                        FilledTonalButton(
                            onClick = { if (isTtsUnlocked) viewModel.testTtsAnnouncement() },
                            enabled = isTtsUnlocked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Aawaz Test Karein (Test Voice)")
                        }
                    }
                }
            }

            // =========================================================================================
            // 8. FLOATING WINDOW OVERLAY
            // =========================================================================================
            item {
                FilterCard(
                    title = filterCopy.overlayTitle,
                    subtitle = filterCopy.overlayDesc,
                    exampleText = filterCopy.overlayExample,
                    onSpeakExplain = { viewModel.speakFilterExplanation(filterCopy.overlaySpeech) },
                    speakContentDescription = filterCopy.listenButtonLabel,
                    icon = Icons.Default.Layers,
                    isEnabled = settingsState.isFloatingOverlayEnabled,
                    onToggle = { enable ->
                        if (isOverlayAdminLocked) return@FilterCard
                        if (!isOverlayUnlocked) {
                            featureToUnlock = FeatureIds.FLOATING_OVERLAY
                        } else if (enable) {
                            if (PermissionUtils.canDrawOverlays(context)) {
                                hasOverlayPermission = true
                                viewModel.toggleFloatingOverlay(true)
                            } else {
                                hasOverlayPermission = false
                                showPermissionDialog = true
                            }
                        } else {
                            viewModel.toggleFloatingOverlay(false)
                        }
                    },
                    testTag = "floating_overlay_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isOverlayUnlocked,
                    isAdminLocked = isOverlayAdminLocked,
                    featureId = FeatureIds.FLOATING_OVERLAY,
                    onUnlockClick = { if (!isOverlayAdminLocked) featureToUnlock = FeatureIds.FLOATING_OVERLAY },
                    remainingTimeText = getRemainingTime(FeatureIds.FLOATING_OVERLAY),
                    pointsPerHour = overlayRate
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Permission Alert Banner if missing
                        if (!hasOverlayPermission) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Screen Button Permission Baaki Hai",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        )
                                        Text(
                                            text = "Settings me jakar 'Display over other apps' ON karein.",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            overlayPermissionLauncher.launch(intent)
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text("ON Karein", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Master Automation Status Callout
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (settingsState.isAutoAcceptEnabled)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (settingsState.isAutoAcceptEnabled)
                                        Icons.Default.CheckCircle
                                    else
                                        Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = if (settingsState.isAutoAcceptEnabled)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = if (settingsState.isAutoAcceptEnabled)
                                            "Screen Par Chalu Hai"
                                        else
                                            "Abhi Band Hai (Home par Start ON karein)",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (settingsState.isAutoAcceptEnabled)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                    Text(
                                        text = if (settingsState.isAutoAcceptEnabled)
                                            "Rapido ke upar chhota button dikhega — 1 tap me pause ya start karein."
                                        else
                                            "Jab aap Home screen par Service ON karenge tab ye button dikhega.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================================
            // 9. AUTO-CLICK MODE (Always visible standalone card — never hidden inside Overlay!)
            // =========================================================================================
            item {
                val autoClickSwitchEnabled = !isAutoClickAdminLocked && hasAnyAutoCapability
                val onAutoClickUnlock = {
                    if (isAutoClickAdminLocked) {
                        Toast.makeText(context, "Auto-Click is locked by Admin", Toast.LENGTH_SHORT).show()
                    } else if (hasAnyEvaluationFilterUnlocked) {
                        featureToUnlock = FeatureIds.AUTO_CLICK
                    } else {
                        multiFilterPreselectedIds = setOf(FeatureIds.MIN_FARE, FeatureIds.AUTO_CLICK)
                        showMultiFilterDialog = true
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auto_click_filter_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (settingsState.isAutoClickEnabled && autoClickSwitchEnabled)
                            MaterialTheme.colorScheme.surface
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (settingsState.isAutoClickEnabled && autoClickSwitchEnabled)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(
                                            if (settingsState.isAutoClickEnabled && autoClickSwitchEnabled)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(10.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (settingsState.isAutoClickEnabled && autoClickSwitchEnabled)
                                            Icons.Default.TouchApp
                                        else
                                            Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        tint = if (settingsState.isAutoClickEnabled && autoClickSwitchEnabled)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = filterCopy.autoClickTitle,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        val isAutoClickOn = settingsState.isAutoClickEnabled && autoClickSwitchEnabled
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isAutoClickOn)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else
                                                MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Text(
                                                text = if (isAutoClickOn) "ON" else "OFF",
                                                maxLines = 1,
                                                softWrap = false,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 10.sp,
                                                    color = if (isAutoClickOn)
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    else
                                                        MaterialTheme.colorScheme.onErrorContainer
                                                ),
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = filterCopy.autoClickDesc,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(5.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = filterCopy.autoClickExample,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                fontWeight = FontWeight.Medium,
                                                lineHeight = 15.sp
                                            ),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(
                                    onClick = { viewModel.speakFilterExplanation(filterCopy.autoClickSpeech) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("auto_click_filter_card_speak_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = filterCopy.listenButtonLabel,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .then(
                                            if (!autoClickSwitchEnabled) {
                                                Modifier.clickable { onAutoClickUnlock() }
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Switch(
                                        checked = settingsState.isAutoClickEnabled && autoClickSwitchEnabled,
                                        onCheckedChange = if (autoClickSwitchEnabled) {
                                            { viewModel.toggleAutoClick(it) }
                                        } else {
                                            null
                                        },
                                        enabled = autoClickSwitchEnabled,
                                        thumbContent = {
                                            Icon(
                                                imageVector = if (settingsState.isAutoClickEnabled && autoClickSwitchEnabled)
                                                    Icons.Default.TouchApp
                                                else
                                                    Icons.Default.RecordVoiceOver,
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize)
                                            )
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            uncheckedThumbColor = Color.White,
                                            uncheckedTrackColor = MaterialTheme.colorScheme.error
                                        ),
                                        modifier = Modifier.testTag("auto_click_switch")
                                    )
                                }
                            }
                        }

                        // Speed / Unlock Status + Last Ride Price
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    isAutoClickAdminLocked -> "Admin ne Auto-Click band kiya hai"
                                    !hasAnyAutoCapability -> "Service check ho rahi hai..."
                                    settingsState.isAutoClickEnabled && isAutoClickUnlocked -> "Tez Auto-Accept ON (300-700ms Pro Speed)"
                                    settingsState.isAutoClickEnabled -> "Free Auto-Accept ON (1-2 sec Speed)"
                                    else -> "Sirf Aawaz Alert ON (Apne aap Accept nahi hoga)"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (settingsState.isAutoClickEnabled && autoClickSwitchEnabled)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            if (!isAutoClickUnlocked && !isAutoClickAdminLocked) {
                                FilledTonalButton(
                                    onClick = onAutoClickUnlock,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "300ms Boost ($autoClickRate Pts/Hr)",
                                        maxLines = 1,
                                        softWrap = false,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Last Accepted Ride Price indicator
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Aakhri Accepted Ride Ka Kiraya:",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                                Text(
                                    text = if (settingsState.lastAcceptedFare != null && settingsState.lastAcceptedFare!! > 0) {
                                        "₹${settingsState.lastAcceptedFare!!.toInt()}"
                                    } else {
                                        "--"
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================================
            // BOTTOM RESET BUTTON: "Sab Reset Karein (Safe Default)"
            // =========================================================================================
            item {
                OutlinedButton(
                    onClick = {
                        viewModel.resetToSafeDefaults()
                        Toast.makeText(context, filterCopy.resetSuccessToast, Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("reset_safe_defaults_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = filterCopy.resetButtonLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Reusable card container for individual modular filter settings with ON/OFF badge,
 * 1-line real-world example, and 48dp "Sunke Samjho" speaker explanation button.
 */
@Composable
private fun FilterCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    testTag: String,
    isProMode: Boolean = true,
    isUnlocked: Boolean = isProMode,
    isAdminLocked: Boolean = false,
    featureId: String? = null,
    onUnlockClick: (() -> Unit)? = null,
    remainingTimeText: String? = null,
    pointsPerHour: Int = 10,
    exampleText: String? = null,
    onSpeakExplain: (() -> Unit)? = null,
    speakContentDescription: String = "Sunke Samjho",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val currentOnUnlockClick by rememberUpdatedState(onUnlockClick)
    val currentOnSpeakExplain by rememberUpdatedState(onSpeakExplain)
    val effectivelyUnlocked = isUnlocked && !isAdminLocked
    val handleLockedInteraction = {
        if (isAdminLocked) {
            Toast.makeText(context, "$title is locked by Admin", Toast.LENGTH_SHORT).show()
        } else {
            currentOnUnlockClick?.invoke()
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .then(
                if (!effectivelyUnlocked) {
                    Modifier.clickable { handleLockedInteraction() }
                } else {
                    Modifier
                }
            )
            .alpha(if (effectivelyUnlocked) 1f else 0.85f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled && effectivelyUnlocked)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isEnabled && effectivelyUnlocked)
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Icon + Title + ON/OFF Badge + Speaker + Switch Toggle
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
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                if (isEnabled && effectivelyUnlocked)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isEnabled && effectivelyUnlocked)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEnabled && effectivelyUnlocked)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            val isActiveOn = isEnabled && effectivelyUnlocked
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isActiveOn) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = if (isActiveOn) "ON" else "OFF",
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp,
                                        color = if (isActiveOn) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    ),
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isAdminLocked) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Locked by Admin",
                                            modifier = Modifier.size(11.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = "Locked by Admin",
                                            maxLines = 1,
                                            softWrap = false,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                            } else if (effectivelyUnlocked && remainingTimeText != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = remainingTimeText,
                                        maxLines = 1,
                                        softWrap = false,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 10.sp
                                        ),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            } else if (!effectivelyUnlocked) {
                                if (onUnlockClick != null) {
                                    FilledTonalButton(
                                        onClick = onUnlockClick,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .heightIn(min = 36.dp)
                                            .testTag("${testTag}_unlock_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Unlock ($pointsPerHour Pts/Hr)",
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = "Pro Feature",
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                            Text(
                                                text = "Pro Feature",
                                                maxLines = 1,
                                                softWrap = false,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    fontSize = 10.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        if (!exampleText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = exampleText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = 15.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (currentOnSpeakExplain != null) {
                        IconButton(
                            onClick = { currentOnSpeakExplain?.invoke() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("${testTag}_speak_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = speakContentDescription,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Individual Toggle Switch
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .then(
                                if (!effectivelyUnlocked) {
                                    Modifier.clickable { handleLockedInteraction() }
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Switch(
                            checked = isEnabled && effectivelyUnlocked,
                            onCheckedChange = if (effectivelyUnlocked) onToggle else null,
                            enabled = effectivelyUnlocked,
                            modifier = Modifier.testTag("${testTag}_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            // Expandable control inputs when filter is active
            AnimatedVisibility(visible = isEnabled && effectivelyUnlocked) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * Sticky 3 Smart Modes 1-Tap Preset Bar pinned at the top of FilterSettingsScreen.
 */
@Composable
private fun SmartModesStickyBar(
    activeMode: AppSettings.SmartMode,
    safeModeLabel: String,
    zyadaKamaiLabel: String,
    customLabel: String,
    headerLabel: String,
    onSelectMode: (AppSettings.SmartMode) -> Unit,
    onApplyLongRoute: () -> Unit = {},
    onResetDefaults: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.testTag("smart_modes_sticky_bar")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = headerLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (activeMode == AppSettings.SmartMode.CUSTOM) {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (activeMode == AppSettings.SmartMode.CUSTOM) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        }
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectMode(AppSettings.SmartMode.CUSTOM) }
                        .testTag("smart_mode_custom")
                ) {
                    Text(
                        text = if (activeMode == AppSettings.SmartMode.CUSTOM) customLabel else "Preset ON",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = if (activeMode == AppSettings.SmartMode.CUSTOM) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmartModeChip(
                    label = safeModeLabel,
                    icon = Icons.Default.Security,
                    isSelected = activeMode == AppSettings.SmartMode.SAFE,
                    selectedColor = MaterialTheme.colorScheme.primary,
                    onClick = { onSelectMode(AppSettings.SmartMode.SAFE) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("smart_mode_safe")
                )
                SmartModeChip(
                    label = zyadaKamaiLabel,
                    icon = Icons.Default.TrendingUp,
                    isSelected = activeMode == AppSettings.SmartMode.ZYADA_KAMAI,
                    selectedColor = MaterialTheme.colorScheme.secondary,
                    onClick = { onSelectMode(AppSettings.SmartMode.ZYADA_KAMAI) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("smart_mode_zyada_kamai")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmartModeChip(
                    label = "Lamba Route",
                    icon = Icons.Default.DirectionsCar,
                    isSelected = false,
                    selectedColor = MaterialTheme.colorScheme.secondary,
                    onClick = onApplyLongRoute,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("smart_mode_lamba_route")
                )
                SmartModeChip(
                    label = "Reset",
                    icon = Icons.Default.RestartAlt,
                    isSelected = false,
                    selectedColor = MaterialTheme.colorScheme.tertiary,
                    onClick = onResetDefaults,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("smart_mode_reset")
                )
            }
        }
    }
}

@Composable
private fun SmartModeChip(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) selectedColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) selectedColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = if (isSelected) selectedColor else MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

/**
 * Live plain-language summary card ("Iska Matlab Kya Hai?") explaining combined filter rules.
 */
@Composable
private fun PlainLanguageSummaryCard(
    heading: String,
    summaryText: String,
    onSpeak: () -> Unit,
    speakLabel: String
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plain_language_summary_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
            IconButton(
                onClick = onSpeak,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("summary_speak_button")
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = speakLabel,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

private data class LocalizedFilterCopy(
    val smartModesHeader: String,
    val safeModeLabel: String,
    val zyadaKamaiLabel: String,
    val customLabel: String,
    val customHintToast: String,
    val summaryHeading: String,
    val listenButtonLabel: String,
    val resetButtonLabel: String,
    val resetSuccessToast: String,
    val minFareTitle: String,
    val minFareDesc: String,
    val minFareExample: String,
    val minFareSpeech: String,
    val maxFareTitle: String,
    val maxFareDesc: String,
    val maxFareExample: String,
    val maxFareSpeech: String,
    val pickupTitle: String,
    val pickupDesc: String,
    val pickupExample: String,
    val pickupSpeech: String,
    val dropTitle: String,
    val dropDesc: String,
    val dropExample: String,
    val dropSpeech: String,
    val minRateTitle: String,
    val minRateDesc: String,
    val minRateExample: String,
    val minRateSpeech: String,
    val blacklistTitle: String,
    val blacklistDesc: String,
    val blacklistExample: String,
    val blacklistSpeech: String,
    val voiceTitle: String,
    val voiceDesc: String,
    val voiceExample: String,
    val voiceSpeech: String,
    val overlayTitle: String,
    val overlayDesc: String,
    val overlayExample: String,
    val overlaySpeech: String,
    val autoClickTitle: String,
    val autoClickDesc: String,
    val autoClickExample: String,
    val autoClickSpeech: String
)

private fun getLocalizedFilterCopy(lang: String): LocalizedFilterCopy {
    return when (lang) {
        "en" -> LocalizedFilterCopy(
            smartModesHeader = "⚡ 1-Tap Smart Modes",
            safeModeLabel = "Safe Mode",
            zyadaKamaiLabel = "Zyada Kamai",
            customLabel = "Custom",
            customHintToast = "Adjust any filter below to customize",
            summaryHeading = "💡 Abhi Kya Set Hai?",
            listenButtonLabel = "Listen Explanation",
            resetButtonLabel = "Reset All (Safe Default)",
            resetSuccessToast = "All filters reset to Safe Default!",
            minFareTitle = "Minimum Ride Fare",
            minFareDesc = "Skip cheap small-fare rides automatically",
            minFareExample = "Example: Set ₹50 so a ₹45 ride is skipped",
            minFareSpeech = "Minimum ride fare. If you set 50 rupees, any ride below 50 rupees will be skipped automatically.",
            maxFareTitle = "Maximum Ride Fare",
            maxFareDesc = "Stop very high-fare or out-of-town rides",
            maxFareExample = "Example: Set ₹500 so a ₹650 long ride is skipped",
            maxFareSpeech = "Maximum ride fare. Set a ceiling like 500 rupees if you do not want super long trips.",
            pickupTitle = "How Far Is Customer (Pickup)",
            pickupDesc = "Avoid driving far empty just to pick up customer",
            pickupExample = "Example: Set 2.5 km so a 4 km pickup is skipped",
            pickupSpeech = "Pickup distance limit. Keep it around 2.5 kilometers so you do not waste fuel reaching far customers.",
            dropTitle = "Max Trip Distance (Drop)",
            dropDesc = "Stay inside city by limiting long drop distances",
            dropExample = "Example: Set 15 km so a 25 km trip is skipped",
            dropSpeech = "Drop distance limit. Set 15 kilometers to avoid going too far outside your preferred city area.",
            minRateTitle = "Earnings Per KM (₹/Km)",
            minRateDesc = "Ensure good fuel profit on every kilometer",
            minRateExample = "Example: Set ₹12/km so 10 km for ₹80 is skipped",
            minRateSpeech = "Rate per kilometer. Set at least 12 rupees per kilometer so every trip covers petrol and gives profit.",
            blacklistTitle = "Avoid Specific Areas",
            blacklistDesc = "Block destinations or areas you do not want to visit",
            blacklistExample = "Example: Add 'Airport' to skip all Airport drops",
            blacklistSpeech = "Avoid specific areas. Type area names like Airport or Highway here, and rides going there will be skipped.",
            voiceTitle = "Voice Ride Alerts (TTS)",
            voiceDesc = "Hear fare and pickup/drop aloud without looking at screen",
            voiceExample = "Example: Speaks 'Fare 85 rupees, Pickup 1 km' aloud",
            voiceSpeech = "Voice ride alerts. Turn this on to hear ride fare, pickup and drop location aloud while riding safely.",
            overlayTitle = "Floating Screen Button",
            overlayDesc = "Small movable button over Rapido to pause/start in 1 tap",
            overlayExample = "Example: Pause or resume auto-accept without leaving Rapido",
            overlaySpeech = "Floating screen button. Shows a small bubble on top of Rapido so you can pause or start anytime with one tap.",
            autoClickTitle = "Auto-Click Accept Button",
            autoClickDesc = "ON accepts matching rides automatically; OFF gives voice alert only",
            autoClickExample = "Example: Green (ON) = Auto Accept • Red (OFF) = Voice Only",
            autoClickSpeech = "Auto click accept button. Keep it ON for automatic ride acceptance, or turn OFF for voice alerts only."
        )
        "hi" -> LocalizedFilterCopy(
            smartModesHeader = "⚡ 1-टैप स्मार्ट मोड",
            safeModeLabel = "सेफ मोड",
            zyadaKamaiLabel = "ज़्यादा कमाई",
            customLabel = "कस्टम",
            customHintToast = "नीचे कोई भी फ़िल्टर अपने हिसाब से बदलें",
            summaryHeading = "💡 अभी क्या सेट है?",
            listenButtonLabel = "सुनके समझें",
            resetButtonLabel = "सब रीसेट करें (सेफ डिफ़ॉल्ट)",
            resetSuccessToast = "सभी फ़िल्टर सेफ डिफ़ॉल्ट पर सेट हो गए!",
            minFareTitle = "कम से कम किराया",
            minFareDesc = "बहुत कम पैसे वाली छोटी राइड अपने आप छोड़ें",
            minFareExample = "जैसे: ₹50 सेट किया तो ₹45 वाली राइड स्किप होगी",
            minFareSpeech = "कम से कम किराया। अगर आपने पचास रुपये सेट किया, तो पचास से कम किराये वाली राइड अपने आप छूट जाएगी।",
            maxFareTitle = "ज़्यादा से ज़्यादा किराया",
            maxFareDesc = "बहुत बड़े किराये वाली लंबी राइड से बचें",
            maxFareExample = "जैसे: ₹500 सेट किया तो ₹650 वाली लंबी राइड स्किप होगी",
            maxFareSpeech = "ज़्यादा से ज़्यादा किराया। अगर आप बहुत लंबी राइड नहीं लेना चाहते तो इसे पांच सौ रुपये पर सेट रखें।",
            pickupTitle = "ग्राहक कितनी दूर हो (पिकअप)",
            pickupDesc = "दूर ग्राहक के पास खाली गाड़ी चलाने से बचें",
            pickupExample = "जैसे: 2.5 किमी सेट किया तो 4 किमी दूर पिकअप स्किप होगा",
            pickupSpeech = "पिकअप दूरी। इसे ढाई किलोमीटर रखें ताकि बहुत दूर ग्राहक को लेने जाने में पेट्रोल खर्च न हो।",
            dropTitle = "राइड कितनी लंबी हो (ड्रॉप)",
            dropDesc = "शहर के बाहर बहुत दूर जाने वाली राइड रोकें",
            dropExample = "जैसे: 15 किमी सेट किया तो 25 किमी की राइड स्किप होगी",
            dropSpeech = "ड्रॉप दूरी। शहर के अंदर रहने के लिए इसे पंद्रह किलोमीटर पर सेट कर सकते हैं।",
            minRateTitle = "हर किमी की कमाई (₹/Km)",
            minRateDesc = "हर किलोमीटर पर पक्का मुनाफ़ा — पेट्रोल नुकसान नहीं",
            minRateExample = "जैसे: ₹12/किमी सेट किया तो 10 किमी में ₹80 वाली राइड स्किप होगी",
            minRateSpeech = "हर किलोमीटर की कमाई। कम से कम बारह रुपये प्रति किलोमीटर रखें ताकि हर राइड में अच्छा मुनाफ़ा मिले।",
            blacklistTitle = "ये जगह बंद करें (ब्लॉक एरिया)",
            blacklistDesc = "जिन इलाकों में नहीं जाना, उनका नाम यहाँ जोड़ें",
            blacklistExample = "जैसे: 'Airport' लिखा तो एयरपोर्ट जाने वाली राइड स्किप होगी",
            blacklistSpeech = "जगह बंद करें। जिस इलाके में आप नहीं जाना चाहते उसका नाम लिखें, वहां की राइड अपने आप छूट जाएगी।",
            voiceTitle = "आवाज़ में राइड बताएं",
            voiceDesc = "स्क्रीन देखे बिना आवाज़ में किराया और जगह सुनें",
            voiceExample = "जैसे: राइड आते ही बोलेगा 'किराया 85 रुपये, पिकअप 1 किमी'",
            voiceSpeech = "आवाज़ अलर्ट। इसे चालू रखने पर राइड आते ही किराया और लोकेशन आवाज़ में सुनाई देगी।",
            overlayTitle = "स्क्रीन पर छोटा बटन",
            overlayDesc = "रैपिडो चलाते समय 1 टैप में चालू/बंद करने का बटन",
            overlayExample = "जैसे: रैपिडो बंद किए बिना यहीं से ऑटो-एक्सेप्ट रोकें",
            overlaySpeech = "स्क्रीन पर छोटा बटन। यह रैपिडो के ऊपर दिखता है ताकि आप एक टैप में ऐप को चालू या बंद कर सकें।",
            autoClickTitle = "अपने आप एक्सेप्ट करें",
            autoClickDesc = "ON पर पसंद की राइड खुद एक्सेप्ट होगी, OFF पर सिर्फ आवाज़",
            autoClickExample = "जैसे: हरा (ON) = खुद एक्सेप्ट • लाल (OFF) = सिर्फ आवाज़",
            autoClickSpeech = "अपने आप एक्सेप्ट बटन। इसे चालू रखेंगे तो सही राइड खुद एक्सेप्ट होगी, वरना सिर्फ आवाज़ में बताएगा।"
        )
        else -> LocalizedFilterCopy(
            smartModesHeader = "⚡ 1-Tap Smart Modes",
            safeModeLabel = "Safe Mode",
            zyadaKamaiLabel = "Zyada Kamai",
            customLabel = "Custom",
            customHintToast = "Neeche kisi bhi filter ko apne hisab se badlein",
            summaryHeading = "💡 Abhi Kya Set Hai?",
            listenButtonLabel = "Sunke Samjho",
            resetButtonLabel = "Sab Reset Karein (Safe Default)",
            resetSuccessToast = "Sabhi filters Safe Default par set ho gaye!",
            minFareTitle = "Kam se Kam Kiraya",
            minFareDesc = "Bahut kam kiraye wali chhoti ride mat lo",
            minFareExample = "Jaise: ₹50 set kiya toh ₹45 wali ride apne aap skip hogi",
            minFareSpeech = "Kam se kam kiraya. Agar aapne pachas rupaye set kiya, toh pachas se kam wali ride apne aap skip ho jayegi.",
            maxFareTitle = "Zyada se Zyada Kiraya",
            maxFareDesc = "Bahut zyada kiraye ya lambi ride ki limit rakhein",
            maxFareExample = "Jaise: ₹500 set kiya toh ₹650 wali lambi ride skip hogi",
            maxFareSpeech = "Zyada se zyada kiraya. Agar aap bahut door ki lambi ride nahi lena chahte toh isko paanch sau rupaye par set karein.",
            pickupTitle = "Customer Kitni Door Ho (Pickup)",
            pickupDesc = "Zyada door pickup ke liye khaali gaadi mat chalao",
            pickupExample = "Jaise: 2.5 km set kiya toh 4 km door wala customer skip hoga",
            pickupSpeech = "Pickup doori. Isko dhai kilometer rakhein taaki zyada door customer lene jaane me petrol kharch na ho.",
            dropTitle = "Ride Kitni Lambi Ho (Drop)",
            dropDesc = "Shehar ke bahar ya bahut lambi trip se bachein",
            dropExample = "Jaise: 15 km set kiya toh 25 km door ki ride skip hogi",
            dropSpeech = "Drop doori. Shehar ke andar rehne ke liye isko pandrah kilometer par set kar sakte hain.",
            minRateTitle = "Har KM Ki Kamai (₹/Km)",
            minRateDesc = "Har kilometer par pakka munafa — petrol ka nuksan nahi",
            minRateExample = "Jaise: ₹12/km set kiya toh 10 km me ₹80 wali ride skip hogi",
            minRateSpeech = "Har kilometer ki kamai. Kam se kam barah rupaye prati kilometer rakhein taaki har ride me acha munafa mile.",
            blacklistTitle = "Ye Jagah Band Karein",
            blacklistDesc = "Jin ilakon me nahi jana, unka naam yahan daalein",
            blacklistExample = "Jaise: 'Airport' likha toh Airport jaane wali ride skip hogi",
            blacklistSpeech = "Ye jagah band karein. Jis area me nahi jana hai uska naam daalein, wahan ki ride apne aap skip ho jayegi.",
            voiceTitle = "Aawaz Me Ride Bataye",
            voiceDesc = "Screen dekhe bina aawaz me kiraya aur jagah sunein",
            voiceExample = "Jaise: Ride aate hi bolega 'Kiraya 85 rupaye, Pickup 1 km'",
            voiceSpeech = "Aawaz me ride bataye. Isko chalu rakhne par nayi ride aate hi kiraya aur jagah aawaz me sunai degi.",
            overlayTitle = "Screen Par Chhota Button",
            overlayDesc = "Rapido chalate waqt screen ke upar 1-tap control button",
            overlayExample = "Jaise: Rapido band kiye bina 1-tap me Auto-Accept rok ya chalu karein",
            overlaySpeech = "Screen par chhota button. Yeh Rapido ke upar dikhta hai jisse aap ek tap me auto accept rok ya chalu kar sakte hain.",
            autoClickTitle = "Apne Aap Accept Karein",
            autoClickDesc = "ON rakhein toh pasand ki ride khud accept hogi, OFF par sirf aawaz",
            autoClickExample = "Jaise: Green (ON) = Khud Accept • Red (OFF) = Sirf Aawaz",
            autoClickSpeech = "Apne aap accept karein. Isko chalu rakhenge toh pasand ki ride khud accept hogi, band par sirf aawaz aayegi."
        )
    }
}

