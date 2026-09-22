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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Layers
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.PermissionUtils
import com.example.ui.OrderMasterViewModel
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSettingsScreen(
    viewModel: OrderMasterViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsState by viewModel.settingsState.collectAsState()
    var languageDropdownExpanded by remember { mutableStateOf(false) }

    var hasOverlayPermission by remember {
        mutableStateOf(PermissionUtils.canDrawOverlays(context))
    }
    var showPermissionDialog by remember { mutableStateOf(false) }

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

            // =========================================================================================
            // 1. MINIMUM FARE FILTER
            // =========================================================================================
            item {
                FilterCard(
                    title = "Minimum Fare Filter",
                    subtitle = "Ignore rides below threshold price",
                    icon = Icons.Default.CurrencyRupee,
                    isEnabled = settingsState.isMinFareEnabled,
                    onToggle = { viewModel.toggleMinFareFilter(it) },
                    testTag = "min_fare_filter_card"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Current Minimum Limit:",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "₹${settingsState.minFare.toInt()}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Slider(
                            value = settingsState.minFare,
                            onValueChange = { viewModel.setMinFare(it.roundToInt().toFloat()) },
                            valueRange = 30f..300f,
                            steps = 26, // Step of ~₹10
                            modifier = Modifier.fillMaxWidth().testTag("min_fare_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )

                        // Quick Select Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(40f, 60f, 80f, 100f, 150f).forEach { presetVal ->
                                OutlinedButton(
                                    onClick = { viewModel.setMinFare(presetVal) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("₹${presetVal.toInt()}", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================================
            // 2. MAXIMUM FARE FILTER
            // =========================================================================================
            item {
                FilterCard(
                    title = "Maximum Fare Filter",
                    subtitle = "Ceiling limit for long/surge rides",
                    icon = Icons.Default.CurrencyRupee,
                    isEnabled = settingsState.isMaxFareEnabled,
                    onToggle = { viewModel.toggleMaxFareFilter(it) },
                    testTag = "max_fare_filter_card"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Current Maximum Limit:",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "₹${settingsState.maxFare.toInt()}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Slider(
                            value = settingsState.maxFare,
                            onValueChange = { viewModel.setMaxFare(it.roundToInt().toFloat()) },
                            valueRange = 200f..10000f,
                            steps = 48,
                            modifier = Modifier.fillMaxWidth().testTag("max_fare_slider")
                        )

                        // Quick Select Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(500f, 1000f, 2500f, 5000f).forEach { presetVal ->
                                OutlinedButton(
                                    onClick = { viewModel.setMaxFare(presetVal) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("₹${presetVal.toInt()}", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
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
                    onToggle = { viewModel.toggleMaxPickupDistFilter(it) },
                    testTag = "pickup_dist_filter_card"
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
                            onValueChange = { viewModel.setMaxPickupDistance((it * 10).roundToInt() / 10f) },
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
                                    onClick = { viewModel.setMaxPickupDistance(distVal) },
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
                    onToggle = { viewModel.toggleMaxDropDistFilter(it) },
                    testTag = "drop_dist_filter_card"
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
                            onValueChange = { viewModel.setMaxDropDistance((it * 2).roundToInt() / 2f) },
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
                                    onClick = { viewModel.setMaxDropDistance(distVal) },
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
            // 5. VOICE ANNOUNCEMENTS (TTS) FILTER
            // =========================================================================================
            item {
                FilterCard(
                    title = "Voice Announcements (TTS)",
                    subtitle = "Audible spoken alerts for parsed fares and status",
                    icon = Icons.Default.RecordVoiceOver,
                    isEnabled = settingsState.isVoiceAnnouncerEnabled,
                    onToggle = { viewModel.toggleVoiceAnnouncer(it) },
                    testTag = "voice_tts_filter_card"
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
                            expanded = languageDropdownExpanded,
                            onExpandedChange = { languageDropdownExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = if (settingsState.voiceLanguage == "hi") "Hinglish / Hindi (हिंदी)" else "English (India)",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageDropdownExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = languageDropdownExpanded,
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
                            onClick = { viewModel.testTtsAnnouncement() },
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
            // 6. FLOATING WINDOW OVERLAY BUTTON
            // =========================================================================================
            item {
                FilterCard(
                    title = "Floating Window Overlay",
                    subtitle = "Movable HUD over driver apps showing fare & quick mode toggle",
                    icon = Icons.Default.Layers,
                    isEnabled = settingsState.isFloatingOverlayEnabled,
                    onToggle = { enable ->
                        if (enable) {
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
                    testTag = "floating_overlay_filter_card"
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
                                Text(
                                    text = "Auto-Click Automation Mode",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = if (settingsState.isAutoClickEnabled)
                                        "🟢 Green HUD: Full Auto Click"
                                    else
                                        "🔴 Red HUD: Voice Alert Only",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = if (settingsState.isAutoClickEnabled) Color(0xFF047857) else Color(0xFFB91C1C),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Switch(
                                checked = settingsState.isAutoClickEnabled,
                                onCheckedChange = { viewModel.toggleAutoClick(it) },
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
            onApplyPreset = { presetName -> viewModel.applyPreset(presetName) },
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
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isEnabled)
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
                                if (isEnabled)
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
                            tint = if (isEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
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
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Expandable control inputs when filter is active
            AnimatedVisibility(visible = isEnabled) {
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
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier
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
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Quick Rule Presets",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Text(
                    text = "Tap to restore",
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
                    modifier = Modifier.weight(1f)
                )
                PresetChip(
                    name = "Short Trips",
                    onClick = { onApplyPreset("short") },
                    modifier = Modifier.weight(1f)
                )
                PresetChip(
                    name = "High Value",
                    onClick = { onApplyPreset("high") },
                    modifier = Modifier.weight(1f)
                )
                PresetChip(
                    name = "Default",
                    onClick = { onApplyPreset("default") },
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
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
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
