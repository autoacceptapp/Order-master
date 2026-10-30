package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import android.widget.Toast
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.auth.GoogleAuthManager
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.theme.PrimaryEmeraldDark
import com.example.ui.theme.SurfaceStroke
import kotlinx.coroutines.launch

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
    onNavigateBack: () -> Unit = {},
    onTriggerGoogleSignIn: () -> Unit = {},
    onNavigateToPaymentVerification: () -> Unit = {},
    onNavigateToCaptainStore: () -> Unit = onNavigateToPaymentVerification
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Reactive licensing and operating mode (Free vs Pro)
    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val isProMode = when (val s = accessStatus) {
        is AccessStatus.PassActive -> s.remainingTimeMs > 0
        is AccessStatus.TrialActive -> s.remainingTimeMs > 0
        else -> false
    }

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
                            contentDescription = stringResource(R.string.settings_cd_navigate_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_title_permissions_setup),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = stringResource(R.string.settings_subtitle_permissions_setup),
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
                            contentDescription = stringResource(R.string.settings_cd_refresh_status),
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

            // Google Account & Cloud Sync Tile
            item {
                val userAuthState by GoogleAuthManager.userAuthState.collectAsState()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_google_auth_card")
                        .clickable { onTriggerGoogleSignIn() },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(38.dp),
                                shadowElevation = 1.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_google_logo),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = if (userAuthState.isLoggedIn)
                                        stringResource(R.string.settings_google_account_title)
                                    else
                                        stringResource(R.string.settings_sign_in_google_title),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (userAuthState.isLoggedIn) {
                                        userAuthState.userEmail ?: stringResource(R.string.settings_cloud_sync_active)
                                    } else {
                                        stringResource(R.string.settings_sync_presets_desc)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!userAuthState.isLoggedIn) {
                            FilledTonalButton(
                                onClick = onTriggerGoogleSignIn,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_btn_connect),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = PrimaryEmerald.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_badge_linked),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryEmeraldDark
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Freemium Operating Mode & Pro Pass Status Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_freemium_mode_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isProMode)
                            MaterialTheme.colorScheme.surface
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (isProMode) PrimaryEmerald.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isProMode) PrimaryEmerald.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.tertiaryContainer
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isProMode) Icons.Default.Check else Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = if (isProMode) PrimaryEmerald else MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (isProMode) "Pro Mode (Full Automation)" else "Free Mode (Filters Locked)",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                        if (!isProMode) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.tertiaryContainer
                                            ) {
                                                Text(
                                                    text = "PRO PASS",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                                    ),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = if (isProMode)
                                            "All automation filters active. Orders are evaluated in 300-700ms with smart auto-reject."
                                        else
                                            "Blind auto-accept with 1-2s delay. Configure filters and unlock rules in the Filter Rules tab or upgrade to Pro Pass.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (!isProMode) {
                            FilledTonalButton(
                                onClick = onNavigateToCaptainStore,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("upgrade_to_pro_button"),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingCart,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Upgrade to Pro Pass in Captain Store",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = onNavigateToCaptainStore,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manage_pass_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingCart,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = PrimaryEmerald
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Manage Pass in Captain Store",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryEmerald
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 2. Section Header: Critical Core Permissions
            item {
                Text(
                    text = stringResource(R.string.settings_section_critical_permissions),
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
                            handlePermissionAction(context, item.id, coroutineScope, onNavigateToPaymentVerification)
                        }
                    }
                )
            }

            // 3. Section Header: Reliability & System Settings
            item {
                Text(
                    text = stringResource(R.string.settings_section_background_survival),
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
                        handlePermissionAction(context, item.id, coroutineScope, onNavigateToPaymentVerification)
                    }
                )
            }

            // 4. Section Header: In-App Updater & Releases
            item {
                Text(
                    text = stringResource(R.string.settings_section_in_app_updater),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            item {
                InAppUpdaterSettingsCard()
            }
        }
    }

    if (showRestrictedSettingsDialog) {
        RestrictedSettingsGuideDialog(
            onDismiss = { showRestrictedSettingsDialog = false },
            onOpenAppInfo = {
                PermissionUtils.openAppInfoSettings(context)
            },
            onNavigateToPaymentVerification = onNavigateToPaymentVerification
        )
    }
}

/**
 * Dispatches the appropriate intent for each permission item.
 */
private fun handlePermissionAction(
    context: android.content.Context,
    permissionId: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onNavigateToPaymentVerification: () -> Unit
) {
    when (permissionId) {
        "accessibility" -> {
            PermissionUtils.invokeRapidoAccessibilityService(context)
        }
        "overlay" -> PermissionUtils.openOverlaySettings(context)
        "battery" -> PermissionUtils.openBatteryOptimizationSettings(context)
        "autostart" -> PermissionUtils.openOemAutoStartSettings(context)
        "notifications" -> PermissionUtils.openNotificationSettings(context)
        "installer" -> PermissionUtils.openInstallUnknownAppsSettings(context)
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
                            text = if (isReady)
                                stringResource(R.string.settings_banner_ready_title)
                            else
                                stringResource(R.string.settings_banner_action_required_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isReady) PrimaryEmerald else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = if (isReady)
                                stringResource(R.string.settings_banner_ready_desc)
                            else
                                stringResource(R.string.settings_banner_action_required_desc),
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
                    Text(
                        text = stringResource(R.string.settings_btn_grant_missing_permissions),
                        fontWeight = FontWeight.Bold
                    )
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
        "installer" -> Icons.Default.SystemUpdate
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
                            text = if (item.isGranted) stringResource(R.string.settings_badge_granted)
                            else if (item.id == "autostart") stringResource(R.string.settings_badge_manual_check)
                            else stringResource(R.string.settings_badge_required),
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
                        contentDescription = stringResource(R.string.settings_btn_manage),
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
                        text = if (item.id == "autostart") stringResource(R.string.settings_btn_manage) else stringResource(R.string.settings_btn_enable),
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
                text = stringResource(R.string.settings_oem_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_oem_desc),
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
                Text(
                    text = stringResource(R.string.settings_oem_btn_open),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun InAppUpdaterSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by GitHubUpdateManager.updateState.collectAsState()
    var isAutoUpdate by remember { mutableStateOf(GitHubUpdateManager.isAutoUpdateEnabled(context)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("in_app_updater_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(SurfaceStroke)
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.settings_updater_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_updater_version_format,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                // Check for updates button
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            GitHubUpdateManager.checkForUpdates(
                                context = context,
                                currentVersion = BuildConfig.VERSION_NAME,
                                currentVersionCode = BuildConfig.VERSION_CODE,
                                ignoreSkipped = true
                            )
                        }
                    },
                    enabled = updateState !is UpdateResult.Checking,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("check_updates_button")
                ) {
                    if (updateState is UpdateResult.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_updater_checking),
                            fontSize = 12.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.settings_updater_check_now),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Update status message
            when (val state = updateState) {
                is UpdateResult.UpdateAvailable -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PrimaryEmerald.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = PrimaryEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_updater_new_version_format, state.latestVersion),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryEmerald
                                )
                            )
                        }
                    }
                }
                is UpdateResult.NoUpdate -> {
                    Text(
                        text = stringResource(R.string.settings_updater_up_to_date_format, state.currentVersion),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = PrimaryEmerald,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                is UpdateResult.Error -> {
                    Text(
                        text = stringResource(R.string.settings_updater_error_format, state.message),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.error
                        )
                    )
                }
                else -> {
                    // Idle state
                }
            }

            // Auto-check toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_updater_auto_check_title),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = stringResource(R.string.settings_updater_auto_check_desc),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isAutoUpdate,
                    onCheckedChange = { checked ->
                        isAutoUpdate = checked
                        GitHubUpdateManager.setAutoUpdateEnabled(context, checked)
                    },
                    modifier = Modifier.testTag("auto_update_toggle"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = PrimaryEmerald
                    )
                )
            }
        }
    }
}

/**
 * Payment Response Double-Check (Frontend):
 * Pass status is updated strictly upon server confirmation.
 */
fun onPaymentSuccess(
    context: android.content.Context,
    paymentId: String,
    onComplete: ((Boolean) -> Unit)? = null
) {
    PassManager.verifyPaymentWithBackend(context, paymentId) { isSuccess ->
        if (isSuccess) {
            PassManager.setPassStatus(context, true)
            android.widget.Toast.makeText(context, context.getString(R.string.settings_toast_pass_activated), android.widget.Toast.LENGTH_SHORT).show()
            onComplete?.invoke(true)
        } else {
            PassManager.setPassStatus(context, false)
            android.widget.Toast.makeText(context, context.getString(R.string.settings_toast_payment_failed), android.widget.Toast.LENGTH_SHORT).show()
            onComplete?.invoke(false)
        }
    }
}

/**
 * Interactive Dialog to Unlock a Specific Premium Feature on an Hourly Pay-Per-Use Basis.
 */
@Composable
fun FeatureUnlockDialog(
    featureId: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pricing = FeatureIds.getPricing(featureId)
    val pointsPerHour = pricing?.pointsPerHour ?: 10
    var hours by remember { mutableStateOf(1) }
    var isLoading by remember { mutableStateOf(false) }
    val pointsBalance by LicenseManager.pointsBalance.collectAsState()
    val totalCost = pointsPerHour * hours
    val hasEnoughPoints = pointsBalance >= totalCost

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Text(
                text = "Unlock ${pricing?.title ?: "Feature"}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                pricing?.description?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Hours selector with Slider and Quick Buttons
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Duration",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "$hours Hour${if (hours > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    Slider(
                        value = hours.toFloat(),
                        onValueChange = { hours = it.toInt().coerceIn(1, 12) },
                        valueRange = 1f..12f,
                        steps = 10,
                        enabled = !isLoading
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 2, 4, 8, 12).forEach { quickHour ->
                            FilledTonalButton(
                                onClick = { hours = quickHour },
                                enabled = !isLoading,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(30.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (hours == quickHour)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (hours == quickHour)
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text(
                                    text = "${quickHour}h",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }

                // Cost Summary Box
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Rate per hour:", style = MaterialTheme.typography.bodySmall)
                            Text(text = "$pointsPerHour Pts", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Your Points Balance:", style = MaterialTheme.typography.bodySmall)
                            Text(text = "$pointsBalance Pts", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total Cost:",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "$totalCost Points",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (hasEnoughPoints) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            )
                        }
                        if (!hasEnoughPoints) {
                            Text(
                                text = "Insufficient points balance (Need ${totalCost - pointsBalance} more pts). Earn points with Refer & Earn.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isLoading || !hasEnoughPoints) return@Button
                    isLoading = true
                    coroutineScope.launch {
                        val result = LicenseManager.unlockFeature(featureId, hours)
                        isLoading = false
                        result.onSuccess {
                            Toast.makeText(
                                context,
                                "Unlocked ${pricing?.title ?: "Feature"} for $hours hr(s)!",
                                Toast.LENGTH_SHORT
                            ).show()
                            onSuccess()
                            onDismiss()
                        }.onFailure { err ->
                            Toast.makeText(
                                context,
                                "Unlock failed: ${err.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = !isLoading && hasEnoughPoints,
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Processing...")
                } else {
                    Text(
                        text = "Pay & Unlock ($totalCost Pts)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

