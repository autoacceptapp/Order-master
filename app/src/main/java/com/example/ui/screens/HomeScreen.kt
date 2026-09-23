package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AccessStatus
import com.example.ActivityLogEntry
import com.example.AppSettings
import com.example.LicenseManager
import com.example.LogSeverity
import com.example.PermissionUtils
import com.example.R
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
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: OrderMasterViewModel,
    onNavigateToSettings: () -> Unit,
    onOpenRestrictedSettingsGuide: () -> Unit,
    onTriggerGoogleSignIn: () -> Unit = {},
    onNavigateToSubscription: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsState by viewModel.settingsState.collectAsState()
    val logs by viewModel.liveLogs.collectAsState()
    val ttsStatus by viewModel.ttsStatus.collectAsState()
    val userAuthState by GoogleAuthManager.userAuthState.collectAsState()
    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val pointsBalance by LicenseManager.pointsBalance.collectAsState()

    val isServiceRunningInSystem = AppSettings.isAccessibilityServiceEnabled(context)
    val isAutomationActive = settingsState.isAutoAcceptEnabled && isServiceRunningInSystem

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =========================================================================================
        // COMPONENT 1: MASTER TOGGLE SWITCH
        // =========================================================================================
        MasterToggleCard(
            isAutomationActive = isAutomationActive,
            isServiceRunningInSystem = isServiceRunningInSystem,
            isAutoAcceptEnabled = settingsState.isAutoAcceptEnabled,
            pulseScale = pulseScale,
            onToggle = {
                if (!isServiceRunningInSystem) {
                    if (PermissionUtils.isAndroid13OrHigher()) {
                        onOpenRestrictedSettingsGuide()
                    } else {
                        PermissionUtils.openAccessibilitySettings(context)
                    }
                } else {
                    viewModel.toggleMasterAutomation()
                }
            },
            onFixService = {
                if (PermissionUtils.isAndroid13OrHigher()) {
                    onOpenRestrictedSettingsGuide()
                } else {
                    PermissionUtils.openAccessibilitySettings(context)
                }
            }
        )

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
            modifier = Modifier.weight(1f)
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
    val activeColor = Color(0xFF10B981) // Emerald Green
    val pausedColor = Color(0xFFF59E0B) // Amber
    val disabledColor = Color(0xFFEF4444) // Coral Red

    val cardBgColor by animateColorAsState(
        targetValue = when {
            !isServiceRunningInSystem -> Color(0xFFFEF2F2)
            isAutoAcceptEnabled -> Color(0xFFECFDF5)
            else -> Color(0xFFFFFBEB)
        },
        label = "cardBgColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            !isServiceRunningInSystem -> disabledColor.copy(alpha = 0.5f)
            isAutoAcceptEnabled -> activeColor.copy(alpha = 0.6f)
            else -> pausedColor.copy(alpha = 0.5f)
        },
        label = "borderColor"
    )

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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Status Badge & Large Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                            text = "AUTOMATION SERVICE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = when {
                                !isServiceRunningInSystem -> "SYSTEM PERMISSION NEEDED"
                                isAutoAcceptEnabled -> "ACTIVE & LISTENING"
                                else -> "PAUSED BY CAPTAIN"
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

                // Tactile Master Switch
                Switch(
                    checked = isAutoAcceptEnabled && isServiceRunningInSystem,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.testTag("master_toggle_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = activeColor,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFD1D5DB)
                    )
                )
            }

            // Description or Alert Banner
            if (!isServiceRunningInSystem) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = disabledColor.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = disabledColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Accessibility service is OFF in device settings.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = disabledColor
                                )
                            )
                        }
                        Button(
                            onClick = onFixService,
                            colors = ButtonDefaults.buttonColors(containerColor = disabledColor),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Enable", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
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
                            "Continuously analyzing ride requests from driver overlay cards."
                        else
                            "Automated acceptance is halted. Tap the switch to resume monitoring.",
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
                                text = if (isAutomationActive) "ACTIVE" else "PAUSED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAutomationActive) activeColor else pausedColor
                                )
                            )
                        }
                    }
                }
            }
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
                        text = "Live Active Monitor",
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
                            modifier = Modifier.size(32.dp)
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

                // Sandbox shortcut bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onSimulateOffer,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Simulate Offer",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
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
                    "Scanning for incoming ride offers..."
                else
                    "Automation Service Paused",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            Text(
                text = if (isAutomationActive)
                    "Overlay cards from ride apps will be parsed and evaluated automatically here in real time."
                else
                    "Enable the master switch above to begin listening for screen offers.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            FilledTonalButton(
                onClick = onSimulateOffer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ElectricBolt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Simulate Sample Ride Offer")
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
                                text = if (isAccepted) "ACCEPTED" else "IGNORED",
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
                        EvaluationChip(label = "Fare", value = "₹${fare.toInt()}")
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
                            text = userName ?: "Google Account Linked",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = userEmail ?: "Cloud Sync Active",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
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
                            text = "Connect Google Account",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Backup filter rules & sync ride logs",
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
                    modifier = Modifier.testTag("home_google_sign_in_button")
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
                        text = "Synced",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF047857)
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
                        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
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
                        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (currentAccess) {
                                is AccessStatus.PassActive -> Icons.Default.CardGiftcard
                                is AccessStatus.TrialActive -> Icons.Default.FlashOn
                                else -> Icons.Default.Lock
                            },
                            contentDescription = null,
                            tint = when (currentAccess) {
                                is AccessStatus.PassActive -> PrimaryEmerald
                                is AccessStatus.TrialActive -> Color(0xFF0284C7)
                                else -> MaterialTheme.colorScheme.error
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
                                is AccessStatus.TrialActive -> "2-Day Free Trial"
                                is AccessStatus.Expired -> "Access Expired"
                                AccessStatus.Loading -> "Verifying..."
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
                    }
                    Text(
                        text = when (currentAccess) {
                            is AccessStatus.PassActive -> "Expires in ${currentAccess.formattedRemaining}"
                            is AccessStatus.TrialActive -> "Remaining: ${currentAccess.formattedRemaining}"
                            is AccessStatus.Expired -> "Clicks paused • Get a pass"
                            AccessStatus.Loading -> "Checking cloud license..."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onOpenStore,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (currentAccess) {
                        is AccessStatus.PassActive -> MaterialTheme.colorScheme.primaryContainer
                        is AccessStatus.TrialActive -> MaterialTheme.colorScheme.secondaryContainer
                        else -> AmberAccent
                    },
                    contentColor = when (currentAccess) {
                        is AccessStatus.PassActive -> MaterialTheme.colorScheme.onPrimaryContainer
                        is AccessStatus.TrialActive -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> Color.Black
                    }
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("home_store_pass_button")
            ) {
                Text(
                    text = when (currentAccess) {
                        is AccessStatus.PassActive -> "Manage"
                        is AccessStatus.TrialActive -> "Store"
                        else -> "Buy Pass"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

