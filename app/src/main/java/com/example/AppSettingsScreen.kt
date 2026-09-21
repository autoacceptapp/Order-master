package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.theme.SurfaceStroke

/**
 * Production-ready Permission & Background Readiness Dashboard (AppSettingsScreen).
 *
 * Provides captains with:
 * - Real-time system permission indicators (Accessibility, Overlay, Battery Saver, Auto-Start).
 * - Direct intent action triggers to respective Android OEM and system configuration screens.
 * - Dynamic readiness banner ("Ready for Auto-Acceptance" vs "Action Required").
 * - Auto-refreshing state whenever the captain returns from system settings (Lifecycle ON_RESUME).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Live state of all permissions
    var permissionList by remember {
        mutableStateOf(PermissionUtils.getPermissionStatusList(context))
    }

    var isReady by remember {
        mutableStateOf(PermissionUtils.isReadyForAutoAcceptance(context))
    }

    var showRestrictedSettingsDialog by remember { mutableStateOf(false) }

    // Refresh permission statuses
    val refreshPermissions = {
        permissionList = PermissionUtils.getPermissionStatusList(context)
        isReady = PermissionUtils.isReadyForAutoAcceptance(context)
    }

    // Auto-refresh when captain returns to this screen from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Intercept back button to navigate smoothly back to Dashboard rather than closing app
    BackHandler {
        onNavigateBack()
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
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("permissions_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate Back to Dashboard",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Permissions & System Setup",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Background Reliability & Auto-Accept",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { refreshPermissions() },
                        modifier = Modifier.testTag("refresh_permissions_button")
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
            // 1. Overall Readiness Summary Banner
            item {
                ReadinessSummaryBanner(
                    isReady = isReady,
                    onOpenPrimaryAction = {
                        if (!PermissionUtils.isAccessibilityServiceEnabled(context)) {
                            if (PermissionUtils.isAndroid13OrHigher()) {
                                showRestrictedSettingsDialog = true
                            } else {
                                PermissionUtils.openAccessibilitySettings(context)
                            }
                        } else if (!PermissionUtils.canDrawOverlays(context)) {
                            PermissionUtils.openOverlaySettings(context)
                        } else if (!PermissionUtils.isIgnoringBatteryOptimizations(context)) {
                            PermissionUtils.openBatteryOptimizationSettings(context)
                        } else {
                            PermissionUtils.openOemAutoStartSettings(context)
                        }
                    }
                )
            }

            // Android 13/14+ Restricted Settings Help Notice
            if (!PermissionUtils.isAccessibilityServiceEnabled(context) && PermissionUtils.isAndroid13OrHigher()) {
                item {
                    RestrictedSettingsNoticeBanner(
                        onClick = { showRestrictedSettingsDialog = true }
                    )
                }
            }

            // 2. Section Header: Critical Core Permissions
            item {
                Text(
                    text = "CRITICAL FOR ORDER ACCEPTANCE",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            // Critical Permission Cards (Accessibility, Overlay, Battery)
            items(
                items = permissionList.filter { it.isCritical },
                key = { it.id }
            ) { item ->
                PermissionItemCard(
                    item = item,
                    onActionClick = {
                        if (item.id == "accessibility" && PermissionUtils.isAndroid13OrHigher()) {
                            showRestrictedSettingsDialog = true
                        } else {
                            handlePermissionAction(context, item.id)
                        }
                    }
                )
            }

            // 3. Section Header: Reliability & System Settings
            item {
                Text(
                    text = "BACKGROUND SURVIVAL & NOTIFICATIONS",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            // Recommended/Secondary Permission Cards
            items(
                items = permissionList.filter { !it.isCritical },
                key = { it.id }
            ) { item ->
                PermissionItemCard(
                    item = item,
                    onActionClick = {
                        handlePermissionAction(context, item.id)
                    }
                )
            }

            // 4. Quick OEM Guidance Footer Card
            item {
                OemGuidanceCard(
                    onOpenOemSettings = {
                        PermissionUtils.openOemAutoStartSettings(context)
                    }
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
                PermissionUtils.openAccessibilitySettings(context)
            }
        )
    }
}

/**
 * Dispatches the appropriate intent for each permission item.
 */
private fun handlePermissionAction(context: android.content.Context, permissionId: String) {
    when (permissionId) {
        "accessibility" -> PermissionUtils.openAccessibilitySettings(context)
        "overlay" -> PermissionUtils.openOverlaySettings(context)
        "battery" -> PermissionUtils.openBatteryOptimizationSettings(context)
        "autostart" -> PermissionUtils.openOemAutoStartSettings(context)
        "notifications" -> PermissionUtils.openNotificationSettings(context)
        "exact_alarm" -> PermissionUtils.openExactAlarmSettings(context)
        else -> PermissionUtils.openAppDetailsSettings(context)
    }
}

@Composable
fun ReadinessSummaryBanner(
    isReady: Boolean,
    onOpenPrimaryAction: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isReady) PrimaryEmerald else MaterialTheme.colorScheme.error,
        label = "banner_border"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .testTag("readiness_summary_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isReady) PrimaryEmerald.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isReady) Icons.Default.Check else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isReady) PrimaryEmerald else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isReady) "Ready for Auto-Acceptance" else "Action Required",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isReady) PrimaryEmerald else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = if (isReady) "All critical background guards are active" else "Missing critical permissions for automation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!isReady) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onOpenPrimaryAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("resolve_permissions_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Grant Missing Critical Permissions", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PermissionItemCard(
    item: AppPermissionItem,
    onActionClick: () -> Unit
) {
    val icon: ImageVector = when (item.id) {
        "accessibility" -> Icons.Default.TouchApp
        "overlay" -> Icons.Default.Layers
        "battery" -> Icons.Default.BatteryChargingFull
        "autostart" -> Icons.Default.PowerSettingsNew
        "notifications" -> Icons.Default.Notifications
        "exact_alarm" -> Icons.Default.Timer
        else -> Icons.Default.Security
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permission_item_${item.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(SurfaceStroke)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with background
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (item.isGranted) PrimaryEmerald.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (item.isGranted) PrimaryEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Badge indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (item.isGranted) PrimaryEmerald.copy(alpha = 0.2f)
                                else if (item.id == "autostart") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (item.isGranted) "GRANTED"
                            else if (item.id == "autostart") "MANUAL CHECK"
                            else "REQUIRED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (item.isGranted) PrimaryEmerald
                            else if (item.id == "autostart") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action button
            if (item.isGranted) {
                IconButton(
                    onClick = onActionClick,
                    modifier = Modifier.testTag("action_icon_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Manage",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onActionClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("grant_button_${item.id}")
                ) {
                    Text(
                        text = if (item.id == "autostart") "Manage" else "Enable",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun OemGuidanceCard(
    onOpenOemSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("oem_guidance_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "💡 Xiaomi / Realme / Oppo / Vivo Captains",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Aggressive OEM battery savers often kill background apps when the screen turns off. Ensure 'Auto-Start' is switched ON and Battery Saver is set to 'No Restrictions'.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onOpenOemSettings,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_oem_autostart_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Open OEM Device Manager / Auto-Start", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
