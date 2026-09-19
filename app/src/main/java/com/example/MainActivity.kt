package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CaptainAutoAcceptTheme
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.theme.SurfaceStroke
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AppSettings.EXTRA_LOG_MESSAGE)
            if (!msg.isNullOrBlank()) {
                AppSettings.addLog(
                    title = "System Broadcast",
                    message = msg,
                    severity = LogSeverity.INFO
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.init(this)

        setContent {
            CaptainAutoAcceptTheme {
                DashboardScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AppSettings.ACTION_ACCESSIBILITY_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(logReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val settingsState by AppSettings.settingsState.collectAsState()
    val logs by AppSettings.logsFlow.collectAsState()

    var isServiceActive by remember {
        mutableStateOf(AppSettings.isAccessibilityServiceEnabled(context))
    }

    // Refresh status on focus
    DisposableEffect(Unit) {
        isServiceActive = AppSettings.isAccessibilityServiceEnabled(context)
        onDispose { }
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PrimaryEmerald),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ElectricBolt,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Captain Auto-Accept",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Ride Request Automation",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isServiceActive = AppSettings.isAccessibilityServiceEnabled(context)
                            Toast.makeText(context, "Status refreshed", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("refresh_status_button")
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
            // 1. Accessibility Service Status Banner
            item {
                AccessibilityStatusBanner(
                    isActive = isServiceActive,
                    onOpenSettings = {
                        try {
                            context.startActivity(AppSettings.createAccessibilitySettingsIntent())
                        } catch (e: Exception) {
                            Toast.makeText(context, "Unable to open Settings: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // 2. Master Automation Switch Card
            item {
                MasterSwitchCard(
                    isEnabled = settingsState.isAutoAcceptEnabled,
                    onToggle = { enabled ->
                        AppSettings.setAutoAcceptEnabled(context, enabled)
                        AppSettings.addLog(
                            title = "Master Switch",
                            message = if (enabled) "Auto-Accept logic enabled." else "Auto-Accept logic paused by captain.",
                            severity = if (enabled) LogSeverity.INFO else LogSeverity.WARNING
                        )
                    }
                )
            }

            // 3. Threshold Filters Card (Fare & Pickup Distance)
            item {
                DecisionThresholdsCard(
                    settings = settingsState,
                    onMinFareChanged = { AppSettings.setMinFare(context, it) },
                    onMaxDistanceChanged = { AppSettings.setMaxPickupDistance(context, it) },
                    onDelayChanged = { AppSettings.setClickDelayMs(context, it) }
                )
            }

            // 4. Interactive Ride Request Sandbox / Simulator
            item {
                RideSimulatorCard(
                    settings = settingsState,
                    onSimulateAccept = { offerText ->
                        val parsed = TextAnalysisEngine.parseRideOffer(offerText)
                        val eval = TextAnalysisEngine.evaluateRideOffer(
                            offer = parsed,
                            minFare = settingsState.minFare,
                            maxPickupDistance = settingsState.maxPickupDistance,
                            isAutoAcceptEnabled = settingsState.isAutoAcceptEnabled
                        )
                        if (eval.isAccepted) {
                            AppSettings.addLog(
                                title = "Simulation: Auto-Accepted!",
                                message = "Test offer meets all criteria. Action click dispatched in ${settingsState.clickDelayMs}ms.",
                                severity = LogSeverity.CLICK_EXECUTED,
                                evaluation = eval
                            )
                        } else {
                            AppSettings.addLog(
                                title = "Simulation: Rejected",
                                message = eval.decisionReason,
                                severity = LogSeverity.REJECTED,
                                evaluation = eval
                            )
                        }
                    }
                )
            }

            // 5. Real-Time Activity Log Feed
            item {
                ActivityLogsSection(
                    logs = logs,
                    onClearLogs = { AppSettings.clearLogs() }
                )
            }
        }
    }
}

@Composable
fun AccessibilityStatusBanner(
    isActive: Boolean,
    onOpenSettings: () -> Unit
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
                    "Background service is listening for ride request overlay cards (Rapido, Uber, Ola) to evaluate fares and pickup distances."
                } else {
                    "Tap 'Enable' to open Android Accessibility settings and toggle 'Smart Text Notification & Analysis Service' to ON."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    onMaxDistanceChanged: (Float) -> Unit,
    onDelayChanged: (Long) -> Unit
) {
    val focusManager = LocalFocusManager.current

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("decision_thresholds_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SurfaceStroke))
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

            Spacer(modifier = Modifier.height(16.dp))

            // --- MINIMUM FARE SETTING ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Minimum Total Fare",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Base fare + tips (e.g. ₹56 + ₹13)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "₹${settings.minFare.roundToInt()}",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryEmerald
                    )
                )
            }

            Slider(
                value = settings.minFare,
                onValueChange = onMinFareChanged,
                valueRange = 20f..300f,
                steps = 27, // increments of 10
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryEmerald,
                    activeTrackColor = PrimaryEmerald
                ),
                modifier = Modifier.testTag("min_fare_slider")
            )

            // Quick Preset Chips for Fare
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val farePresets = listOf(40f, 60f, 80f, 100f, 150f, 200f)
                farePresets.forEach { preset ->
                    val isSelected = (settings.minFare.roundToInt() == preset.toInt())
                    FilterChip(
                        selected = isSelected,
                        onClick = { onMinFareChanged(preset) },
                        label = { Text("₹${preset.toInt()}", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryEmerald,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- MAXIMUM PICKUP DISTANCE ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Maximum Pickup Distance",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Ignore rides further than this",
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

            Slider(
                value = settings.maxPickupDistance,
                onValueChange = onMaxDistanceChanged,
                valueRange = 0.5f..10.0f,
                steps = 18,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.secondary,
                    activeTrackColor = MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.testTag("max_distance_slider")
            )

            // Quick Preset Chips for Distance
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val distancePresets = listOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 7.0f)
                distancePresets.forEach { preset ->
                    val isSelected = (String.format(Locale.US, "%.1f", settings.maxPickupDistance) == String.format(Locale.US, "%.1f", preset))
                    FilterChip(
                        selected = isSelected,
                        onClick = { onMaxDistanceChanged(preset) },
                        label = { Text("${preset}km", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            selectedLabelColor = Color.Black
                        )
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
                    FilterChip(
                        selected = isSelected,
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
fun RideSimulatorCard(
    settings: SettingsState,
    onSimulateAccept: (String) -> Unit
) {
    var offerInput by remember {
        mutableStateOf("Rapido Ride: ₹65 + ₹15 • Pickup: 1.2 km • Drop: 5.8 km [Accept]")
    }

    // Real-time evaluation of the typed offer
    val parsedOffer = remember(offerInput) {
        TextAnalysisEngine.parseRideOffer(offerInput)
    }

    val evaluation = remember(parsedOffer, settings) {
        TextAnalysisEngine.evaluateRideOffer(
            offer = parsedOffer,
            minFare = settings.minFare,
            maxPickupDistance = settings.maxPickupDistance,
            isAutoAcceptEnabled = settings.isAutoAcceptEnabled
        )
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
                    "Standard (₹80)" to "Rapido Ride: ₹65 + ₹15 • Pickup: 1.2 km • Drop: 5.8 km [Accept]",
                    "High Fare (₹118)" to "Captain Offer: ₹95 + ₹23 • Pickup 2.1 km • Drop 8.0 km [Accept]",
                    "Low Fare (₹35)" to "Order Offer: ₹35 • Pickup 0.8 km • Drop 2.0 km [Accept]",
                    "Far Pickup (6.5km)" to "Offer: ₹120 • Pickup 6.5 km • Drop 14.0 km [Accept]"
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
                            text = parsedOffer.totalFare?.let { "₹${it.toInt()}" } ?: "Not Found",
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
                        modifier = Modifier.size(32.dp).testTag("clear_logs_button")
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
