package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PriceChange
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AppSettings
import com.example.ui.OrderMasterViewModel
import com.example.ui.components.FareFilterCard

/**
 * FilterRulesScreen:
 * Displays all modular automation filters with an interactive, rich "Fare Range Filter" section.
 *
 * Requirements fulfilled:
 * - Baseline ranges:
 *   * Min Fare Range: 20f to 500f (Default: 50f)
 *   * Max Fare Range: 100f to 2000f (Default: 500f)
 * - Two interactive `FareFilterCard` components for Min Fare and Max Fare.
 * - Reactive state synchronization with `AppSettings` via `OrderMasterViewModel`.
 * - Cross-boundary validation ensuring minFare cannot exceed maxFare.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterRulesScreen(
    viewModel: OrderMasterViewModel,
    modifier: Modifier = Modifier
) {
    val settingsState by viewModel.settingsState.collectAsState()

    // Preset Chip lists per requirements
    val minFarePresets = listOf(40f, 60f, 80f, 100f, 150f)
    val maxFarePresets = listOf(200f, 300f, 500f, 800f, 1000f)

    val emeraldGreen = Color(0xFF00E676)

    // Validation warning calculation
    val minExceedsMax = settingsState.isMinFareEnabled &&
            settingsState.isMaxFareEnabled &&
            settingsState.minFare > settingsState.maxFare

    val minWarning = if (minExceedsMax) {
        "Min Fare (₹${settingsState.minFare.toInt()}) cannot exceed Max Fare (₹${settingsState.maxFare.toInt()})"
    } else null

    val maxWarning = if (minExceedsMax) {
        "Max Fare (₹${settingsState.maxFare.toInt()}) must be at least Min Fare (₹${settingsState.minFare.toInt()})"
    } else null

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .testTag("filter_rules_list"),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Section Header ---
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(emeraldGreen.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = emeraldGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Fare Range & Filter Rules",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        text = "Customize trip price thresholds and dispatch distance limits",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }

        // =========================================================================
        // 1. MINIMUM FARE FILTER CARD
        // =========================================================================
        item {
            FareFilterCard(
                title = "Minimum Fare Filter",
                description = "Ignore rides paying below this baseline threshold",
                icon = Icons.Default.CurrencyRupee,
                isEnabled = settingsState.isMinFareEnabled,
                onToggle = { enabled -> viewModel.toggleMinFareFilter(enabled) },
                value = settingsState.minFare,
                onValueChange = { newMin -> viewModel.setMinFare(newMin) },
                valueRange = AppSettings.MIN_FARE_RANGE_START..AppSettings.MIN_FARE_RANGE_END,
                presetValues = minFarePresets,
                accentColor = emeraldGreen,
                validationWarning = minWarning,
                testTag = "min_fare_filter_card"
            )
        }

        // =========================================================================
        // 2. MAXIMUM FARE FILTER CARD
        // =========================================================================
        item {
            FareFilterCard(
                title = "Maximum Fare Filter",
                description = "Cap long-distance / outstation surge offers",
                icon = Icons.Default.PriceChange,
                isEnabled = settingsState.isMaxFareEnabled,
                onToggle = { enabled -> viewModel.toggleMaxFareFilter(enabled) },
                value = settingsState.maxFare,
                onValueChange = { newMax -> viewModel.setMaxFare(newMax) },
                valueRange = AppSettings.MAX_FARE_RANGE_START..AppSettings.MAX_FARE_RANGE_END,
                presetValues = maxFarePresets,
                accentColor = emeraldGreen,
                secondaryBadgeColor = MaterialTheme.colorScheme.secondaryContainer,
                validationWarning = maxWarning,
                testTag = "max_fare_filter_card"
            )
        }

        // =========================================================================
        // 3. MAXIMUM PICKUP DISTANCE (0.0 km - 3.0 km)
        // =========================================================================
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("max_pickup_card"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (settingsState.isMaxPickupDistanceEnabled) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NearMe,
                                    contentDescription = "Pickup Distance",
                                    tint = if (settingsState.isMaxPickupDistanceEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Maximum Pickup Distance",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Strict limit to reach customer (0.0 - 3.0 km)",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        Switch(
                            checked = settingsState.isMaxPickupDistanceEnabled,
                            onCheckedChange = { viewModel.toggleMaxPickupDistFilter(it) },
                            modifier = Modifier.testTag("max_pickup_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = emeraldGreen
                            )
                        )
                    }

                    if (settingsState.isMaxPickupDistanceEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Max Reach Distance:",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = emeraldGreen.copy(alpha = 0.16f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, emeraldGreen.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = String.format("%.1f km", settingsState.maxPickupDistance),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = emeraldGreen
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Slider(
                            value = settingsState.maxPickupDistance,
                            onValueChange = { viewModel.setMaxPickupDistance(it) },
                            valueRange = AppSettings.MIN_PICKUP_DISTANCE_KM..AppSettings.MAX_PICKUP_DISTANCE_KM,
                            steps = 5,
                            modifier = Modifier.fillMaxWidth().testTag("pickup_dist_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = emeraldGreen,
                                activeTrackColor = emeraldGreen,
                                inactiveTrackColor = emeraldGreen.copy(alpha = 0.20f)
                            )
                        )
                    }
                }
            }
        }
    }
}
