package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AppSettings
import com.example.PermissionUtils
import com.example.LicenseManager
import com.example.AccessStatus
import com.example.FeatureIds
import com.example.FeatureUnlockDialog
import com.example.ui.OrderMasterViewModel
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.example.ui.components.FareFilterCard
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSettingsScreen(
    viewModel: OrderMasterViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsState by viewModel.settingsState.collectAsState()
    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val unlockedFeatures by LicenseManager.unlockedFeatures.collectAsState()
    val pointsBalance by LicenseManager.pointsBalance.collectAsState()

    val isProMode = when (val s = accessStatus) {
        is AccessStatus.PassActive -> s.remainingTimeMs > 0
        is AccessStatus.TrialActive -> s.remainingTimeMs > 0
        else -> false
    }

    var featureToUnlock by remember { mutableStateOf<String?>(null) }

    fun getRemainingTime(fId: String): String? {
        if (isProMode) return "PRO ACTIVE"
        val ms = LicenseManager.getFeatureRemainingTimeMs(fId)
        if (ms <= 0L) return null
        val totalMins = (ms / 60000L).coerceAtLeast(1L)
        val hrs = totalMins / 60
        val mins = totalMins % 60
        return if (hrs > 0) "${hrs}h ${mins}m left" else "${mins}m left"
    }

    val isFareUnlocked = isProMode || LicenseManager.isFeatureUnlocked(FeatureIds.FARE_FILTERS)
    val isDistanceUnlocked = isProMode || LicenseManager.isFeatureUnlocked(FeatureIds.DISTANCE_FILTERS)
    val isBlacklistUnlocked = isProMode || LicenseManager.isFeatureUnlocked(FeatureIds.DESTINATION_BLACKLIST)
    val isTtsUnlocked = isProMode || LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE)
    val isOverlayUnlocked = isProMode || LicenseManager.isFeatureUnlocked(FeatureIds.FLOATING_OVERLAY)
    val isAutoClickUnlocked = isProMode || LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK)

    var languageDropdownExpanded by remember { mutableStateOf(false) }
    var newBlacklistKeyword by remember { mutableStateOf("") }

    var hasOverlayPermission by remember {
        mutableStateOf(PermissionUtils.canDrawOverlays(context))
    }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // Feature Unlock Micro-transaction Dialog
    featureToUnlock?.let { fId ->
        FeatureUnlockDialog(
            featureId = fId,
            onDismiss = { featureToUnlock = null },
            onSuccess = { featureToUnlock = null }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("filter_settings_list"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Screen Section Header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "Modular Automation Rules",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "Individual on/off toggle for each filtering rule.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (isProMode) "PRO MODE ACTIVE" else "FREEMIUM MODE",
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
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isProMode)
                                    "All filter rules unlocked. Ultra-fast auto-acceptance (300-700ms) with smart auto-reject."
                                else
                                    "Unlock individual filter rules hourly with points, or upgrade to Pro Pass in the Captain Store for unlimited access.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            )
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
                    title = "Minimum Fare Filter",
                    description = "Ignore rides paying below this baseline threshold",
                    icon = Icons.Default.CurrencyRupee,
                    isEnabled = settingsState.isMinFareEnabled,
                    onToggle = { if (isFareUnlocked) viewModel.toggleMinFareFilter(it) },
                    value = settingsState.minFare,
                    onValueChange = { if (isFareUnlocked) viewModel.setMinFare(it) },
                    valueRange = AppSettings.MIN_FARE_RANGE_START..AppSettings.MIN_FARE_RANGE_END,
                    presetValues = listOf(40f, 60f, 80f, 100f, 150f),
                    accentColor = Color(0xFF00E676),
                    validationWarning = if (minExceedsMax) "Minimum fare cannot exceed maximum fare (₹${settingsState.maxFare.toInt()})" else null,
                    testTag = "min_fare_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isFareUnlocked,
                    featureId = FeatureIds.FARE_FILTERS,
                    onUnlockClick = { featureToUnlock = FeatureIds.FARE_FILTERS },
                    remainingTimeText = getRemainingTime(FeatureIds.FARE_FILTERS),
                    pointsPerHour = FeatureIds.getPricing(FeatureIds.FARE_FILTERS)?.pointsPerHour ?: 10
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
                    title = "Maximum Fare Filter",
                    description = "Ceiling limit for long-distance and surge rides",
                    icon = Icons.Default.CurrencyRupee,
                    isEnabled = settingsState.isMaxFareEnabled,
                    onToggle = { if (isFareUnlocked) viewModel.toggleMaxFareFilter(it) },
                    value = settingsState.maxFare,
                    onValueChange = { if (isFareUnlocked) viewModel.setMaxFare(it) },
                    valueRange = AppSettings.MAX_FARE_RANGE_START..AppSettings.MAX_FARE_RANGE_END,
                    presetValues = listOf(200f, 300f, 500f, 800f, 1000f),
                    accentColor = Color(0xFF00E676),
                    secondaryBadgeColor = MaterialTheme.colorScheme.secondaryContainer,
                    validationWarning = if (minExceedsMax) "Maximum fare must be at least minimum fare (₹${settingsState.minFare.toInt()})" else null,
                    testTag = "max_fare_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isFareUnlocked,
                    featureId = FeatureIds.FARE_FILTERS,
                    onUnlockClick = { featureToUnlock = FeatureIds.FARE_FILTERS },
                    remainingTimeText = getRemainingTime(FeatureIds.FARE_FILTERS),
                    pointsPerHour = FeatureIds.getPricing(FeatureIds.FARE_FILTERS)?.pointsPerHour ?: 10
                )
            }

            // =========================================================================================
            // 3. MAXIMUM PICKUP DISTANCE FILTER (0.0 km to 3.0 km)
            // =========================================================================================
            item {
                FilterCard(
                    title = "Maximum Pickup Distance Filter",
                    subtitle = "Strict limit to reach customer (0.0 - 3.0 km)",
                    icon = Icons.Default.NearMe,
                    isEnabled = settingsState.isMaxPickupDistanceEnabled,
                    onToggle = { if (isDistanceUnlocked) viewModel.toggleMaxPickupDistFilter(it) },
                    testTag = "pickup_dist_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isDistanceUnlocked,
                    featureId = FeatureIds.DISTANCE_FILTERS,
                    onUnlockClick = { featureToUnlock = FeatureIds.DISTANCE_FILTERS },
                    remainingTimeText = getRemainingTime(FeatureIds.DISTANCE_FILTERS),
                    pointsPerHour = FeatureIds.getPricing(FeatureIds.DISTANCE_FILTERS)?.pointsPerHour ?: 10
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Max Allowed Pickup:",
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
                            value = settingsState.maxPickupDistance,
                            enabled = isDistanceUnlocked,
                            onValueChange = { if (isDistanceUnlocked) viewModel.setMaxPickupDistance((it * 10).roundToInt() / 10f) },
                            valueRange = 0.0f..3.0f,
                            steps = 29, // 0.1km steps
                            modifier = Modifier.fillMaxWidth().testTag("pickup_dist_slider")
                        )

                        // Quick Select Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { distVal ->
                                OutlinedButton(
                                    onClick = { if (isDistanceUnlocked) viewModel.setMaxPickupDistance(distVal) },
                                    enabled = isDistanceUnlocked,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("${distVal}km", fontSize = 11.sp)
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
                    title = "Maximum Drop Distance Filter",
                    subtitle = "Filter out rides with excessive trip distance",
                    icon = Icons.Default.DirectionsCar,
                    isEnabled = settingsState.isMaxDropDistanceEnabled,
                    onToggle = { if (isDistanceUnlocked) viewModel.toggleMaxDropDistFilter(it) },
                    testTag = "drop_dist_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isDistanceUnlocked,
                    featureId = FeatureIds.DISTANCE_FILTERS,
                    onUnlockClick = { featureToUnlock = FeatureIds.DISTANCE_FILTERS },
                    remainingTimeText = getRemainingTime(FeatureIds.DISTANCE_FILTERS),
                    pointsPerHour = FeatureIds.getPricing(FeatureIds.DISTANCE_FILTERS)?.pointsPerHour ?: 10
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Max Trip Distance:",
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
                            value = settingsState.maxDropDistance,
                            enabled = isDistanceUnlocked,
                            onValueChange = { if (isDistanceUnlocked) viewModel.setMaxDropDistance((it * 2).roundToInt() / 2f) },
                            valueRange = 1.0f..30.0f,
                            steps = 57, // 0.5km steps
                            modifier = Modifier.fillMaxWidth().testTag("drop_dist_slider")
                        )

                        // Quick Select Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(5.0f, 10.0f, 15.0f, 20.0f, 30.0f).forEach { distVal ->
                                OutlinedButton(
                                    onClick = { if (isDistanceUnlocked) viewModel.setMaxDropDistance(distVal) },
                                    enabled = isDistanceUnlocked,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("${distVal.toInt()}km", fontSize = 11.sp)
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
                    title = "Minimum Rate Per-Km Filter",
                    subtitle = "Reject offers paying less than ₹/km (fare ÷ total trip distance)",
                    icon = Icons.Default.Speed,
                    isEnabled = settingsState.isMinPerKmRateEnabled,
                    onToggle = { if (isFareUnlocked) viewModel.toggleMinPerKmRate(it) },
                    testTag = "min_per_km_rate_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isFareUnlocked,
                    featureId = FeatureIds.FARE_FILTERS,
                    onUnlockClick = { featureToUnlock = FeatureIds.FARE_FILTERS },
                    remainingTimeText = getRemainingTime(FeatureIds.FARE_FILTERS),
                    pointsPerHour = FeatureIds.getPricing(FeatureIds.FARE_FILTERS)?.pointsPerHour ?: 10
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Minimum ₹/Km Required:",
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
                            enabled = isFareUnlocked,
                            onValueChange = { if (isFareUnlocked) viewModel.setMinPerKmRate(it.roundToInt().toFloat()) },
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
                                OutlinedButton(
                                    onClick = { if (isFareUnlocked) viewModel.setMinPerKmRate(rateVal) },
                                    enabled = isFareUnlocked,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("₹${rateVal.toInt()}", fontSize = 11.sp)
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
                    title = "Destination Blacklist Filter",
                    subtitle = "Auto-reject rides going to unwanted locations or landmarks",
                    icon = Icons.Default.LocationOff,
                    isEnabled = settingsState.isDestinationBlacklistEnabled,
                    onToggle = { if (isBlacklistUnlocked) viewModel.toggleDestinationBlacklist(it) },
                    testTag = "destination_blacklist_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isBlacklistUnlocked,
                    featureId = FeatureIds.DESTINATION_BLACKLIST,
                    onUnlockClick = { featureToUnlock = FeatureIds.DESTINATION_BLACKLIST },
                    remainingTimeText = getRemainingTime(FeatureIds.DESTINATION_BLACKLIST),
                    pointsPerHour = FeatureIds.getPricing(FeatureIds.DESTINATION_BLACKLIST)?.pointsPerHour ?: 5
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
                                placeholder = { Text("e.g. Airport, Slum, Toll", fontSize = 13.sp) },
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
                                modifier = Modifier.testTag("add_blacklist_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Keyword",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add", fontSize = 12.sp)
                            }
                        }

                        if (settingsState.blockedLocations.isEmpty()) {
                            Text(
                                text = "No blacklisted areas configured. Add landmark names above to auto-reject matching offers.",
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
                    title = "Voice Announcements (TTS)",
                    subtitle = "Audible spoken alerts for parsed fares and status",
                    icon = Icons.Default.RecordVoiceOver,
                    isEnabled = settingsState.isVoiceAnnouncerEnabled,
                    onToggle = { if (isTtsUnlocked) viewModel.toggleVoiceAnnouncer(it) },
                    testTag = "voice_tts_filter_card",
                    isProMode = isProMode,
                    isUnlocked = isTtsUnlocked,
                    featureId = FeatureIds.TTS_VOICE,
                    onUnlockClick = { featureToUnlock = FeatureIds.TTS_VOICE },
                    remainingTimeText = getRemainingTime(FeatureIds.TTS_VOICE),
                    pointsPerHour = FeatureIds.getPricing(FeatureIds.TTS_VOICE)?.pointsPerHour ?: 10
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Speech Language Preference:",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        )

                        // Language Selection Dropdown
                        ExposedDropdownMenuBox(
                            expanded = languageDropdownExpanded && isTtsUnlocked,
                            onExpandedChange = { if (isTtsUnlocked) languageDropdownExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = if (settingsState.voiceLanguage == "hi") "Hinglish / Hindi (हिंदी)" else "English (India)",
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
                                    text = { Text("English (India)") },
                                    onClick = {
                                        viewModel.setVoiceLanguage("en")
                                        languageDropdownExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Hinglish / Hindi (हिंदी)") },
                                    onClick = {
                                        viewModel.setVoiceLanguage("hi")
                                        languageDropdownExpanded = false
                                    }
                                )
                            }
                        }

                        // Test Audio Button
                        FilledTonalButton(
                            onClick = { if (isTtsUnlocked) viewModel.testTtsAnnouncement() },
                            enabled = isTtsUnlocked,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Voice Announcement")
                        }
                    }
                }
            }

            // =========================================================================================
            // 8. FLOATING WINDOW OVERLAY & AUTO-CLICK MODE
            // =========================================================================================
            item {
                FilterCard(
                    title = "Floating Window Overlay",
                    subtitle = "Movable HUD over driver apps showing fare & quick mode toggle",
                    icon = Icons.Default.Layers,
                    isEnabled = settingsState.isFloatingOverlayEnabled,
                    onToggle = { enable ->
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
                    featureId = FeatureIds.FLOATING_OVERLAY,
                    onUnlockClick = { featureToUnlock = FeatureIds.FLOATING_OVERLAY },
                    remainingTimeText = getRemainingTime(FeatureIds.FLOATING_OVERLAY),
                    pointsPerHour = FeatureIds.getPricing(FeatureIds.FLOATING_OVERLAY)?.pointsPerHour ?: 5
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
                                            text = "Overlay Permission Missing",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        )
                                        Text(
                                            text = "Tap to grant 'Display over other apps' in Android settings.",
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
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Grant", fontSize = 12.sp)
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
                                            "Visible on Screen"
                                        else
                                            "Hidden (Master Toggle is OFF)",
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
                                            "Draggable floating HUD is displayed over Rapido / driver screens."
                                        else
                                            "Overlay automatically hides when Master Automation is disabled.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }

                        // Visual Modes & Current Mode Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Auto-Click Automation Mode",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    if (isAutoClickUnlocked && getRemainingTime(FeatureIds.AUTO_CLICK) != null) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF00E676).copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = getRemainingTime(FeatureIds.AUTO_CLICK)!!,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF00E676),
                                                    fontSize = 9.sp
                                                ),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else if (!isAutoClickUnlocked) {
                                        FilledTonalButton(
                                            onClick = { featureToUnlock = FeatureIds.AUTO_CLICK },
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(10.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "Unlock (15 Pts/Hr)",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = if (!isAutoClickUnlocked)
                                        "🔒 Feature Locked (15 Pts/Hr or Pro Pass)"
                                    else if (settingsState.isAutoClickEnabled)
                                        "🟢 Green HUD: Full Auto Click"
                                    else
                                        "🔴 Red HUD: Voice Alert Only",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = if (!isAutoClickUnlocked)
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        else if (settingsState.isAutoClickEnabled)
                                            Color(0xFF047857)
                                        else
                                            Color(0xFFB91C1C),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Switch(
                                checked = settingsState.isAutoClickEnabled,
                                onCheckedChange = {
                                    if (!isAutoClickUnlocked) {
                                        featureToUnlock = FeatureIds.AUTO_CLICK
                                    } else {
                                        viewModel.toggleAutoClick(it)
                                    }
                                },
                                enabled = isAutoClickUnlocked,
                                thumbContent = {
                                    Icon(
                                        imageVector = if (settingsState.isAutoClickEnabled) Icons.Default.TouchApp else Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF10B981),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFEF4444)
                                ),
                                modifier = Modifier.testTag("auto_click_switch")
                            )
                        }

                        // Dynamic Price Display Indicator
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                if (settingsState.isAutoClickEnabled) Color(0xFF10B981) else Color(0xFFEF4444),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (settingsState.isAutoClickEnabled) "A" else "V",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = "Last Accepted Ride Price:",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }

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

            // Bottom Spacing for Presets Bar
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // =========================================================================================
        // 6. SAVE/RESTORE QUICK PRESETS BAR AT THE BOTTOM
        // =========================================================================================
        QuickPresetsBar(
            onApplyPreset = { presetName -> if (isProMode) viewModel.applyPreset(presetName) },
            isProMode = isProMode,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
    }
}

/**
 * Reusable card container for individual modular filter settings with an on/off switch.
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
    featureId: String? = null,
    onUnlockClick: (() -> Unit)? = null,
    remainingTimeText: String? = null,
    pointsPerHour: Int = 10,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .alpha(if (isUnlocked) 1f else 0.85f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled && isUnlocked)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isEnabled && isUnlocked)
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
            // Header: Icon + Title + Individual Switch Toggle
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
                            .size(36.dp)
                            .background(
                                if (isEnabled && isUnlocked)
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
                            tint = if (isEnabled && isUnlocked)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEnabled && isUnlocked)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            if (isUnlocked && remainingTimeText != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF00E676).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = remainingTimeText,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00E676),
                                            fontSize = 9.sp
                                        ),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else if (!isUnlocked) {
                                if (onUnlockClick != null) {
                                    FilledTonalButton(
                                        onClick = onUnlockClick,
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier
                                            .height(26.dp)
                                            .testTag("${testTag}_unlock_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "Unlock ($pointsPerHour Pts/Hr)",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.sp,
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
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                // Individual Toggle Switch
                Switch(
                    checked = isEnabled && isUnlocked,
                    onCheckedChange = if (isUnlocked) onToggle else null,
                    enabled = isUnlocked,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Expandable control inputs when filter is active
            AnimatedVisibility(visible = isEnabled && isUnlocked) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * Save/Restore Quick Presets bar at the bottom.
 */
@Composable
private fun QuickPresetsBar(
    onApplyPreset: (String) -> Unit,
    modifier: Modifier = Modifier,
    isProMode: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.alpha(if (isProMode) 1f else 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isProMode) Icons.Default.Speed else Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isProMode) "Quick Rule Presets" else "Quick Rule Presets (Pro Feature)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Text(
                    text = if (isProMode) "Tap to restore" else "Pro Only",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PresetChip(
                    name = "Peak Hours",
                    onClick = { onApplyPreset("peak") },
                    enabled = isProMode,
                    modifier = Modifier.weight(1f)
                )
                PresetChip(
                    name = "Short Trips",
                    onClick = { onApplyPreset("short") },
                    enabled = isProMode,
                    modifier = Modifier.weight(1f)
                )
                PresetChip(
                    name = "High Value",
                    onClick = { onApplyPreset("high") },
                    enabled = isProMode,
                    modifier = Modifier.weight(1f)
                )
                PresetChip(
                    name = "Default",
                    onClick = { onApplyPreset("default") },
                    enabled = isProMode,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        modifier = modifier.height(32.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
    }
}
