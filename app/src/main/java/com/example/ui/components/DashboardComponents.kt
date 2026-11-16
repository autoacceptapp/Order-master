package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ActivityLogEntry
import com.example.AppSettings
import com.example.FeatureIds
import com.example.LicenseManager
import com.example.LogSeverity
import com.example.PermissionUtils
import com.example.SettingsState
import com.example.TextAnalysisEngine
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.theme.SurfaceStroke
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AccessibilityStatusBanner(
    isActive: Boolean,
    onOpenSettings: () -> Unit,
    onShowRestrictedGuide: (() -> Unit)? = null
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

            if (!isActive && PermissionUtils.isAndroid13OrHigher()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AmberAccent.copy(alpha = 0.15f))
                        .clickable { onShowRestrictedGuide?.invoke() ?: onOpenSettings() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("restricted_setting_hint_link"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Android 13+ restricted setting alert? Tap for 4-step unlock guide",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = AmberAccent,
                        fontSize = 11.sp
                    )
                }
            }
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
    onMaxFareChanged: (Float) -> Unit = {},
    onMaxDistanceChanged: (Float) -> Unit,
    onDelayChanged: (Long) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val unlockedFeatures by LicenseManager.unlockedFeatures.collectAsState()
    val lockedFeatures by LicenseManager.lockedFeatures.collectAsState()
    val isMinFareUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) {
        LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE)
    }
    val isMaxFareUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) {
        LicenseManager.isFeatureUnlocked(FeatureIds.MAX_FARE)
    }
    val isPickupUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) {
        LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE)
    }
    val isAutoClickUnlocked = remember(accessStatus, unlockedFeatures, lockedFeatures) {
        LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK)
    }
    val hasAnyAutoCapability = remember(accessStatus, unlockedFeatures, lockedFeatures) {
        LicenseManager.hasAnyAutoAcceptCapability()
    }
    val canAdjustBothFares = isMinFareUnlocked && isMaxFareUnlocked

    var minPriceInput by remember(settings.minFare) {
        mutableStateOf(settings.minFare.roundToInt().toString())
    }
    var maxPriceInput by remember(settings.maxFare) {
        mutableStateOf(settings.maxFare.roundToInt().toString())
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("decision_thresholds_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(SurfaceStroke))
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

            if (!isMinFareUnlocked || !isMaxFareUnlocked || !isPickupUnlocked) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Filters Locked",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Unlock filters in the Filters tab with Points or activate a Pro Pass to customize locked thresholds.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- PRICE RANGE FILTER HEADER & TEXT INPUTS ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Price Range Filter (\u20B9)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Filter: Min Price \u2264 Fare \u2264 Max Price",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "\u20B9${settings.minFare.roundToInt()} - \u20B9${settings.maxFare.roundToInt()}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryEmerald
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dual Numeric OutlinedTextFields for Exact Minimum and Maximum Price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = minPriceInput,
                    enabled = isMinFareUnlocked,
                    onValueChange = { input ->
                        minPriceInput = input.filter { it.isDigit() }
                        minPriceInput.toFloatOrNull()?.let { newVal ->
                            if (newVal in AppSettings.MIN_FARE_RANGE_START..AppSettings.MIN_FARE_RANGE_END &&
                                newVal <= settings.maxFare
                            ) {
                                onMinFareChanged(newVal)
                            }
                        }
                    },
                    label = { Text("Min Price (\u20B9)", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val maxAllowedMin = minOf(AppSettings.MIN_FARE_RANGE_END, settings.maxFare)
                            val clamped = (minPriceInput.toFloatOrNull() ?: settings.minFare)
                                .coerceIn(AppSettings.MIN_FARE_RANGE_START, maxAllowedMin)
                            minPriceInput = clamped.roundToInt().toString()
                            onMinFareChanged(clamped)
                            focusManager.clearFocus()
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryEmerald,
                        cursorColor = PrimaryEmerald
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                val parsed = minPriceInput.toFloatOrNull()
                                if (parsed == null ||
                                    parsed < AppSettings.MIN_FARE_RANGE_START ||
                                    parsed > AppSettings.MIN_FARE_RANGE_END ||
                                    parsed > settings.maxFare
                                ) {
                                    minPriceInput = settings.minFare.roundToInt().toString()
                                }
                            }
                        }
                        .testTag("min_price_input")
                )

                OutlinedTextField(
                    value = maxPriceInput,
                    enabled = isMaxFareUnlocked,
                    onValueChange = { input ->
                        maxPriceInput = input.filter { it.isDigit() }
                        maxPriceInput.toFloatOrNull()?.let { newVal ->
                            if (newVal in AppSettings.MAX_FARE_RANGE_START..AppSettings.MAX_FARE_RANGE_END &&
                                newVal >= settings.minFare
                            ) {
                                onMaxFareChanged(newVal)
                            }
                        }
                    },
                    label = { Text("Max Price (\u20B9)", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val minAllowedMax = maxOf(AppSettings.MAX_FARE_RANGE_START, settings.minFare)
                            val clamped = (maxPriceInput.toFloatOrNull() ?: settings.maxFare)
                                .coerceIn(minAllowedMax, AppSettings.MAX_FARE_RANGE_END)
                            maxPriceInput = clamped.roundToInt().toString()
                            onMaxFareChanged(clamped)
                            focusManager.clearFocus()
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryEmerald,
                        cursorColor = PrimaryEmerald
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                val parsed = maxPriceInput.toFloatOrNull()
                                if (parsed == null ||
                                    parsed < AppSettings.MAX_FARE_RANGE_START ||
                                    parsed > AppSettings.MAX_FARE_RANGE_END ||
                                    parsed < settings.minFare
                                ) {
                                    maxPriceInput = settings.maxFare.roundToInt().toString()
                                }
                            }
                        }
                        .testTag("max_price_input")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Compose RangeSlider aligned strictly to AppSettings bounds (MIN_FARE_RANGE_START..MAX_FARE_RANGE_END)
            val sliderStart = AppSettings.MIN_FARE_RANGE_START
            val sliderEnd = AppSettings.MAX_FARE_RANGE_END
            val clampedSliderMin = settings.minFare.coerceIn(sliderStart, AppSettings.MIN_FARE_RANGE_END.coerceAtMost(sliderEnd))
            val clampedSliderMax = settings.maxFare.coerceIn(clampedSliderMin.coerceAtLeast(AppSettings.MAX_FARE_RANGE_START), sliderEnd)
            val currentRange = clampedSliderMin..clampedSliderMax
            RangeSlider(
                value = currentRange,
                enabled = canAdjustBothFares,
                onValueChange = { range ->
                    val newMin = range.start.roundToInt().toFloat().coerceIn(
                        AppSettings.MIN_FARE_RANGE_START,
                        AppSettings.MIN_FARE_RANGE_END
                    )
                    val newMax = range.endInclusive.roundToInt().toFloat().coerceIn(
                        maxOf(AppSettings.MAX_FARE_RANGE_START, newMin),
                        AppSettings.MAX_FARE_RANGE_END
                    )
                    if (newMin > settings.maxFare) {
                        onMaxFareChanged(newMax)
                        onMinFareChanged(newMin)
                    } else {
                        onMinFareChanged(newMin)
                        onMaxFareChanged(newMax)
                    }
                },
                valueRange = sliderStart..sliderEnd,
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryEmerald,
                    activeTrackColor = PrimaryEmerald,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("price_range_slider")
            )

            // Quick Preset Chips for Min & Max Price Ranges (aligned strictly within MIN_FARE_RANGE_START..MAX_FARE_RANGE_END)
            Text(
                text = "Quick Presets:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AppSettings.FARE_RANGE_PRESETS.forEach { (label, minVal, maxVal) ->
                    val clampedMin = AppSettings.validateMinFare(minVal, maxVal)
                    val clampedMax = AppSettings.validateMaxFare(maxVal, clampedMin)
                    val isSelected = canAdjustBothFares &&
                        settings.minFare.roundToInt() == clampedMin.roundToInt() &&
                        settings.maxFare.roundToInt() == clampedMax.roundToInt()
                    FilterChip(
                        selected = isSelected,
                        enabled = canAdjustBothFares,
                        onClick = {
                            if (clampedMin > settings.maxFare) {
                                onMaxFareChanged(clampedMax)
                                onMinFareChanged(clampedMin)
                            } else {
                                onMinFareChanged(clampedMin)
                                onMaxFareChanged(clampedMax)
                            }
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryEmerald,
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.testTag("price_preset_${clampedMin.toInt()}_${clampedMax.toInt()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- MAXIMUM PICKUP DISTANCE (0.0 km - 15.0 km) ---
            var pickupDistanceInput by remember(settings.maxPickupDistance) {
                mutableStateOf(String.format(Locale.US, "%.1f", settings.maxPickupDistance))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Maximum Pickup Distance (0.0 km - 15.0 km)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Ignore rides further than this (Max threshold: 15.0 km)",
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

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Slider(
                    value = settings.maxPickupDistance.coerceIn(
                        com.example.AppSettings.MIN_PICKUP_DISTANCE_KM,
                        com.example.AppSettings.MAX_PICKUP_DISTANCE_KM
                    ),
                    enabled = isPickupUnlocked,
                    onValueChange = { newVal ->
                        val rounded = (Math.round(newVal * 10.0f) / 10.0f).coerceIn(
                            com.example.AppSettings.MIN_PICKUP_DISTANCE_KM,
                            com.example.AppSettings.MAX_PICKUP_DISTANCE_KM
                        )
                        onMaxDistanceChanged(rounded)
                    },
                    valueRange = com.example.AppSettings.MIN_PICKUP_DISTANCE_KM..com.example.AppSettings.MAX_PICKUP_DISTANCE_KM,
                    steps = 0, // Smooth 0.1km snapping from 0.0 to 15.0
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.secondary,
                        activeTrackColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("max_distance_slider")
                )

                OutlinedTextField(
                    value = pickupDistanceInput,
                    enabled = isPickupUnlocked,
                    onValueChange = { input ->
                        pickupDistanceInput = input
                        input.toFloatOrNull()?.let { parsed ->
                            if (parsed in com.example.AppSettings.MIN_PICKUP_DISTANCE_KM..com.example.AppSettings.MAX_PICKUP_DISTANCE_KM) {
                                onMaxDistanceChanged(parsed)
                            }
                        }
                    },
                    label = { Text("km", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .width(90.dp)
                        .testTag("pickup_distance_input")
                )
            }

            // Quick Preset Chips for Distance (within 0.0 - 15.0 km)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val distancePresets = listOf(1.0f, 2.0f, 3.0f, 5.0f, 10.0f, 15.0f)
                distancePresets.forEach { preset ->
                    val isSelected = isPickupUnlocked &&
                        (String.format(Locale.US, "%.1f", settings.maxPickupDistance) == String.format(Locale.US, "%.1f", preset))
                    FilterChip(
                        selected = isSelected,
                        enabled = isPickupUnlocked,
                        onClick = { onMaxDistanceChanged(preset) },
                        label = { Text("${preset} km", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.testTag("pickup_preset_${(preset * 10).toInt()}")
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
                    val isDelayAllowed = isAutoClickUnlocked || (hasAnyAutoCapability && delay >= 1000L)
                    FilterChip(
                        selected = isSelected,
                        enabled = isDelayAllowed,
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
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(SurfaceStroke))
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
                        label = { Text("Hindi (\u0939\u093F\u0928\u094D\u0926\u0940)", fontSize = 12.sp) },
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
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedLanguage.equals("hi", ignoreCase = true)) {
                            "Test Speech (\u092A\u0930\u0940\u0915\u094D\u0937\u0923 \u0906\u0935\u093E\u091C\u093C \u0938\u0941\u0928\u0947\u0902)"
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
        mutableStateOf("Rapido Ride: \u20B965 + \u20B915 \u2022 Pickup: 1.2 km \u2022 Drop: 5.8 km [Accept]")
    }

    val parsedOffer = remember(offerInput) {
        TextAnalysisEngine.parseRideOffer(offerInput)
    }

    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val unlockedFeatures by LicenseManager.unlockedFeatures.collectAsState()
    val lockedFeatures by LicenseManager.lockedFeatures.collectAsState()

    val evaluation = remember(parsedOffer, settings, accessStatus, unlockedFeatures, lockedFeatures) {
        val rawEval = TextAnalysisEngine.evaluateRideOffer(
            offer = parsedOffer,
            settings = settings
        )
        val isProOrTrial = LicenseManager.isAccessGranted()
        val hasActiveEvalFilter = settings.isMinFareEnabled || settings.isMaxFareEnabled ||
            settings.isMaxPickupDistanceEnabled || settings.isMaxDropDistanceEnabled ||
            settings.isMinPerKmRateEnabled || settings.isDestinationBlacklistEnabled
        if (!isProOrTrial && !hasActiveEvalFilter) {
            rawEval.copy(
                isAccepted = false,
                decisionReason = "Free Mode: Unlock and enable at least 1 filter (or activate a Pro Pass) to evaluate rides."
            )
        } else {
            rawEval
        }
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
                    "Standard (\u20B980)" to "Rapido Ride: \u20B965 + \u20B915 \u2022 Pickup: 1.2 km \u2022 Drop: 5.8 km [Accept]",
                    "High Fare (\u20B9118)" to "Captain Offer: \u20B995 + \u20B923 \u2022 Pickup 2.1 km \u2022 Drop 8.0 km [Accept]",
                    "Low Fare (\u20B935)" to "Order Offer: \u20B935 \u2022 Pickup 0.8 km \u2022 Drop 2.0 km [Accept]",
                    "Exceeds Max (\u20B9650)" to "Luxury Trip: \u20B9650 \u2022 Pickup 1.5 km \u2022 Drop 22.0 km [Accept]",
                    "Far Pickup (6.5km)" to "Offer: \u20B9120 \u2022 Pickup 6.5 km \u2022 Drop 14.0 km [Accept]"
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
                            text = parsedOffer.totalFare?.let { "\u20B9${it.toInt()}" } ?: "Not Found",
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
                        modifier = Modifier.size(48.dp).testTag("clear_logs_button")
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
