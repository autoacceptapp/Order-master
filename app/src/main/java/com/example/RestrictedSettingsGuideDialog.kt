package com.example

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.OnAmberContainer
import com.example.ui.theme.PrimaryEmerald

private const val OEM_HEALTH_PREFS = "oem_health_checklist_prefs"
private const val KEY_OEM_AUTOSTART_VISITED = "oem_autostart_visited"

/**
 * Interactive 3-step visual guide & dialog for Android 13/14+ Restricted Settings:
 * `Open App Info → Tap ⋮ 3-dots → Allow restricted settings`
 * (with quick-launch into Accessibility Settings to unlock the ⋮ menu if hidden).
 */
@Composable
fun RestrictedSettingsGuideDialog(
    onDismiss: () -> Unit,
    onOpenAppInfo: (() -> Unit)? = null,
    onOpenAccessibility: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var activeVisualStep by remember { mutableIntStateOf(0) }

    val handleOpenAppInfo: () -> Unit = {
        activeVisualStep = 1
        if (onOpenAppInfo != null) {
            onOpenAppInfo()
        } else {
            PermissionUtils.openAppInfoSettings(context)
        }
    }

    val handleOpenAccessibility: () -> Unit = {
        activeVisualStep = 0
        if (onOpenAccessibility != null) {
            onOpenAccessibility()
        } else {
            PermissionUtils.openAccessibilitySettings(context)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp)
                .testTag("restricted_settings_guide_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with Close Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AmberAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Restricted Settings Security Guide",
                                tint = AmberAccent,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Allow Restricted Settings",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AmberAccent.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Android 13\u201316 Sideload Unlock",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = AmberAccent
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_restricted_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Restricted Settings Guide",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Interactive 3-Step Visual Strip: Open App Info -> Tap ⋮ 3-dots -> Allow restricted settings
                VisualThreeStepFlowStrip(
                    activeStep = activeVisualStep,
                    onSelectStep = { activeVisualStep = it }
                )

                // Educational Context Banner
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = AmberAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Apps installed directly from GitHub APK show 'Restricted setting' on Android 13+. Unlock it in 3 quick taps: Open App Info \u2192 Tap \u22EE (3-dots) \u2192 Allow restricted settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }

                // Step-by-Step Instructions
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StepInstructionItem(
                        stepNumber = 1,
                        title = "Open App Info",
                        description = "Open Order Master's App Info screen. (If the \u22EE 3-dots icon is hidden, tap Accessibility once first so Android shows it.)",
                        icon = Icons.Default.Settings,
                        isHighlighted = activeVisualStep == 0,
                        actionLabel = "Open App Info",
                        onActionClick = handleOpenAppInfo,
                        actionTestTag = "step2_open_app_info_btn"
                    )

                    StepInstructionItem(
                        stepNumber = 2,
                        title = "Tap \u22EE (3-dots) in top-right",
                        description = "Look at the top-right corner of App Info and tap the 3-dots (\u22EE) menu icon.",
                        icon = Icons.Default.MoreVert,
                        isHighlighted = activeVisualStep == 1,
                        badgeText = "Top-right \u22EE"
                    )

                    StepInstructionItem(
                        stepNumber = 3,
                        title = "Allow restricted settings & Enable",
                        description = "Tap 'Allow restricted settings', verify with Fingerprint/PIN, then return to Accessibility Settings and switch '${context.getString(R.string.accessibility_service_name)}' ON.",
                        icon = Icons.Default.Fingerprint,
                        isHighlighted = activeVisualStep == 2,
                        actionLabel = "Open Accessibility",
                        onActionClick = handleOpenAccessibility,
                        actionTestTag = "step1_open_accessibility_btn"
                    )
                }

                // Troubleshooting Tip
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = AmberContainer.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AmberAccent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Not seeing the \u22EE 3-dots menu?",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = OnAmberContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Android hides the \u22EE option until you tap the greyed-out Accessibility service once. Tap 'Open Accessibility Settings' below, tap Order Master once, press Back, then open App Info.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnAmberContainer,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Action Buttons Footer
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = handleOpenAppInfo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_app_info_settings_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberAccent,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "1. Open App Info (\u22EE \u2192 Allow Restricted)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Button(
                        onClick = handleOpenAccessibility,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_accessibility_settings_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryEmerald,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessibilityNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "2. Open Accessibility Settings",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("dismiss_restricted_dialog_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "I Understand / Close",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualThreeStepFlowStrip(
    activeStep: Int,
    onSelectStep: (Int) -> Unit
) {
    val steps = listOf(
        Triple("Open\nApp Info", Icons.Default.Settings, 0),
        Triple("Tap \u22EE\n3-Dots", Icons.Default.MoreVert, 1),
        Triple("Allow\nRestricted", Icons.Default.LockOpen, 2)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag("restricted_visual_3step_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        steps.forEachIndexed { index, (label, icon, stepIdx) ->
            val isSelected = activeStep == stepIdx
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) AmberAccent.copy(alpha = 0.18f) else Color.Transparent
                    )
                    .clickable { onSelectStep(stepIdx) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) AmberAccent else MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        lineHeight = 13.sp
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (index < steps.lastIndex) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = AmberAccent.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Individual step presentation item with sequential badge, icon, and optional quick-action.
 */
@Composable
private fun StepInstructionItem(
    stepNumber: Int,
    title: String,
    description: String,
    icon: ImageVector,
    isHighlighted: Boolean = false,
    badgeText: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionTestTag: String? = null
) {
    val borderColor = if (isHighlighted) AmberAccent.copy(alpha = 0.65f) else Color.Transparent
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) {
                AmberContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isHighlighted) {
                    Modifier.border(1.dp, borderColor, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (isHighlighted) AmberAccent else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$stepNumber",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isHighlighted) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isHighlighted) AmberAccent else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                badgeText?.let {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 38.dp)
            )

            if (actionLabel != null && onActionClick != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 38.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButtonWithTag(
                        text = actionLabel,
                        onClick = onActionClick,
                        testTag = actionTestTag
                    )
                }
            }
        }
    }
}

@Composable
private fun TextButtonWithTag(
    text: String,
    onClick: () -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * 1-Tap Permission & OEM Health Checklist Card for the Dashboard and Settings screens.
 * Tracks live status for Accessibility Service, Floating Overlay, Battery Optimization Off,
 * and OEM Auto-Start (Xiaomi/Oppo/Vivo/Realme) with direct 1-tap system intents and
 * Android 13/14 Restricted Settings unlock guidance.
 */
@Composable
fun PermissionOemHealthChecklistCard(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    isBatteryUnrestricted: Boolean,
    onClickAccessibility: () -> Unit,
    onClickOverlay: () -> Unit,
    onClickBattery: () -> Unit,
    onClickOemAutoStart: () -> Unit,
    onOpenRestrictedGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(OEM_HEALTH_PREFS, Context.MODE_PRIVATE)
    }
    var isOemVisited by remember {
        mutableStateOf(prefs.getBoolean(KEY_OEM_AUTOSTART_VISITED, false))
    }
    val isAggressiveOem = remember { PermissionUtils.isAggressiveOemDevice() }
    val brandName = remember { PermissionUtils.getDeviceBrandName() }

    val coreReadyCount = listOf(
        isAccessibilityEnabled,
        isOverlayEnabled,
        isBatteryUnrestricted
    ).count { it }
    val totalReadyCount = coreReadyCount + if (isOemVisited || !isAggressiveOem) 1 else 0
    val allHealthy = isAccessibilityEnabled && isOverlayEnabled && isBatteryUnrestricted && (isOemVisited || !isAggressiveOem)

    var expanded by remember(allHealthy) { mutableStateOf(!allHealthy) }

    val accentColor = if (allHealthy) PrimaryEmerald else AmberAccent

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .testTag("permission_oem_health_checklist_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row (collapsible when all healthy)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable { expanded = !expanded }
                    .testTag("health_checklist_header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (allHealthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = "Setup Health Status",
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Zaroori Permissions Checklist",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (allHealthy) {
                            "Sab 4 checks ready hain ($brandName)"
                        } else {
                            "$totalReadyCount/4 ready • 1 tap me theek karein"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse checklist" else "Expand checklist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthChecklistRow(
                    title = "Accessibility Service",
                    subtitle = if (isAccessibilityEnabled) {
                        "Chalu hai • Rapido rides padh raha hai"
                    } else if (PermissionUtils.isAndroid13OrHigher()) {
                        "Zaroori • Tap karke ON karein (Android 13+ guide)"
                    } else {
                        "Zaroori • Tap karke ON karein"
                    },
                    icon = Icons.Default.AccessibilityNew,
                    isGranted = isAccessibilityEnabled,
                    actionLabel = if (isAccessibilityEnabled) "ON Hai" else "ON Karein",
                    onClick = onClickAccessibility,
                    testTag = "health_row_accessibility"
                )

                HealthChecklistRow(
                    title = "Floating Overlay",
                    subtitle = if (isOverlayEnabled) {
                        "Rapido ke upar chhota button chalu"
                    } else {
                        "Screen par button dikhane ke liye allow karein"
                    },
                    icon = Icons.Default.Layers,
                    isGranted = isOverlayEnabled,
                    actionLabel = if (isOverlayEnabled) "ON Hai" else "Allow",
                    onClick = onClickOverlay,
                    testTag = "health_row_overlay"
                )

                HealthChecklistRow(
                    title = "Battery Saver Band",
                    subtitle = if (isBatteryUnrestricted) {
                        "Background me bina ruke chalega"
                    } else {
                        "App background me band na ho isliye fix karein"
                    },
                    icon = Icons.Default.BatteryChargingFull,
                    isGranted = isBatteryUnrestricted,
                    actionLabel = if (isBatteryUnrestricted) "ON Hai" else "Fix",
                    onClick = onClickBattery,
                    testTag = "health_row_battery"
                )

                HealthChecklistRow(
                    title = "OEM Auto-Start ($brandName)",
                    subtitle = if (isOemVisited) {
                        "Auto-Start check ho gaya • Dobara dekhein"
                    } else if (isAggressiveOem) {
                        "Xiaomi/Oppo/Vivo/Realme me zaroori setting"
                    } else {
                        "Background startup permission (optional)"
                    },
                    icon = Icons.Default.PowerSettingsNew,
                    isGranted = isOemVisited || !isAggressiveOem,
                    actionLabel = if (isOemVisited) "Check" else "Open",
                    onClick = {
                        prefs.edit().putBoolean(KEY_OEM_AUTOSTART_VISITED, true).apply()
                        isOemVisited = true
                        onClickOemAutoStart()
                    },
                    testTag = "health_row_oem_autostart"
                )

                    if (!isAccessibilityEnabled && PermissionUtils.isAndroid13OrHigher()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AmberContainer.copy(alpha = 0.28f))
                                .border(1.dp, AmberAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable(onClick = onOpenRestrictedGuide)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("health_open_restricted_guide_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Restricted Settings Unlock",
                                    tint = AmberAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Seeing 'Restricted setting'? Open 3-step unlock guide",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthChecklistRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isGranted: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    testTag: String
) {
    val statusColor = if (isGranted) PrimaryEmerald else AmberAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isGranted) {
                    PrimaryEmerald.copy(alpha = 0.08f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                contentDescription = title,
                tint = statusColor,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(statusColor.copy(alpha = 0.18f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isGranted) PrimaryEmerald else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Notice banner designed for Android 13 & 14+ home and permissions screens,
 * allowing users to easily launch the Restricted Settings Guide anytime.
 */
@Composable
fun RestrictedSettingsNoticeBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, AmberAccent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .testTag("restricted_settings_notice_banner"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = AmberContainer.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AmberAccent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Restricted Setting",
                        tint = AmberAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = "Android 13+ Restricted Setting Guide",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "App Info \u2192 \u22EE 3-dots \u2192 Allow restricted settings (3-step guide).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "View Guide",
                tint = AmberAccent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
