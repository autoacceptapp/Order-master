package com.example.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.GlobalAdminManager
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.PrimaryEmerald

/**
 * UI State for the Admin Dashboard.
 */
data class AdminDashboardUiState(
    val isServiceActive: Boolean = true,
    val offlineMessage: String = "Service is currently offline for scheduled maintenance.",
    val isRulesPending: Boolean = false,
    val isLoading: Boolean = false,
    val statusMessage: String? = null
)

/**
 * Admin Dashboard Screen:
 * Displays administrative controls including the Rules Pending Notice Card (if active)
 * and the HeroServiceSwitchCard. Developer telemetry and client reactivity code snippets
 * have been cleaned up and removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isServiceActiveFromManager by GlobalAdminManager.isServiceActive.collectAsState()
    val offlineMessageFromManager by GlobalAdminManager.offlineMessage.collectAsState()

    var uiState by remember {
        mutableStateOf(
            AdminDashboardUiState(
                isServiceActive = isServiceActiveFromManager,
                offlineMessage = offlineMessageFromManager
            )
        )
    }

    // Keep uiState in sync with GlobalAdminManager
    LaunchedEffect(isServiceActiveFromManager, offlineMessageFromManager) {
        uiState = uiState.copy(
            isServiceActive = isServiceActiveFromManager,
            offlineMessage = offlineMessageFromManager
        )
    }

    var isUpdating by remember { mutableStateOf(false) }

    @Suppress("UNUSED_PARAMETER")
    fun toggleService(newActiveState: Boolean) {
        isUpdating = false
        Toast.makeText(
            context,
            "Global kill switch is read-only here; manage it from the dedicated Admin App.",
            Toast.LENGTH_SHORT
        ).show()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Admin Dashboard",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("admin_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.testTag("admin_dashboard_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Rules Pending Notice Card (if active)
            if (uiState.isRulesPending) {
                RulesPendingNoticeCard()
            }

            // Hero Service Switch Card
            HeroServiceSwitchCard(
                isServiceActive = uiState.isServiceActive,
                offlineMessage = uiState.offlineMessage,
                isUpdating = isUpdating,
                onToggleService = { newState ->
                    toggleService(newState)
                },
                onMessageChange = { newMsg ->
                    uiState = uiState.copy(offlineMessage = newMsg)
                }
            )
        }
    }
}

/**
 * Rules Pending Notice Card
 * Displayed when Firestore rules or admin security updates are pending deployment.
 */
@Composable
fun RulesPendingNoticeCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("rules_pending_notice_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AmberAccent.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AmberAccent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Pending Rules",
                    tint = AmberAccent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Security Rules Deployment Pending",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Zero-trust security policies are awaiting verification. Verify in Firebase console.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Hero Service Switch Card
 * Primary administrative control to enable or kill the global automation service in real-time.
 */
@Composable
fun HeroServiceSwitchCard(
    isServiceActive: Boolean,
    offlineMessage: String,
    isUpdating: Boolean = false,
    onToggleService: (Boolean) -> Unit,
    onMessageChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeColor = PrimaryEmerald
    val inactiveColor = MaterialTheme.colorScheme.error

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hero_service_switch_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Icon + Status + Toggle Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isServiceActive) activeColor.copy(alpha = 0.15f)
                                else inactiveColor.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isServiceActive) Icons.Filled.CheckCircle else Icons.Filled.PowerSettingsNew,
                            contentDescription = if (isServiceActive) "Active" else "Offline",
                            tint = if (isServiceActive) activeColor else inactiveColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Global Service Switch",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isServiceActive) "ONLINE • Accepting Orders" else "OFFLINE • Maintenance Active",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isServiceActive) activeColor else inactiveColor
                        )
                    }
                }

                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Switch(
                        checked = isServiceActive,
                        onCheckedChange = onToggleService,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = activeColor,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = inactiveColor
                        ),
                        modifier = Modifier.testTag("service_toggle_switch")
                    )
                }
            }

            // Description
            Text(
                text = if (isServiceActive) {
                    "When active, all verified drivers can automatically detect, filter, and accept orders according to their configured thresholds."
                } else {
                    "The service is killed globally. All driver automation is halted, and a maintenance screen is displayed to active users."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Maintenance Message (Shown when offline or editable)
            if (!isServiceActive) {
                OutlinedTextField(
                    value = offlineMessage,
                    onValueChange = onMessageChange,
                    label = { Text("Maintenance / Offline Message") },
                    placeholder = { Text("Message displayed to captains...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offline_message_field"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    maxLines = 3
                )
            }
        }
    }
}
