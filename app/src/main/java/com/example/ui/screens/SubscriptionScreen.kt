package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AccessStatus
import com.example.DeviceUtils
import com.example.LicenseManager
import com.example.PassTier
import com.example.R
import com.example.auth.GoogleAuthManager
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.theme.PrimaryEmeraldDark
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onNavigateBack: () -> Unit,
    onTriggerGoogleSignIn: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val pointsBalance by LicenseManager.pointsBalance.collectAsState()
    val userAuthState by GoogleAuthManager.userAuthState.collectAsState()

    var showTopUpDialog by remember { mutableStateOf(false) }
    var selectedPassForPurchase by remember { mutableStateOf<PassTier?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val hardwareId = remember { DeviceUtils.getDeviceHardwareId(context) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                        modifier = Modifier.testTag("subscription_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate Back"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Captain Store & Passes",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Points Wallet & Cloud Licensing",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                },
                actions = {
                    // Quick Points Pill in TopAppBar
                    Surface(
                        onClick = { showTopUpDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        color = AmberAccent.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("top_bar_points_pill")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "$pointsBalance Pts",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Points",
                                tint = AmberAccent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. User Gmail Profile & Wallet Card
            item {
                WalletHeaderCard(
                    pointsBalance = pointsBalance,
                    isLoggedIn = userAuthState.isLoggedIn,
                    userName = userAuthState.userName,
                    userEmail = userAuthState.userEmail,
                    onTopUp = { showTopUpDialog = true },
                    onSignIn = onTriggerGoogleSignIn
                )
            }

            // 2. Access / Trial Status Banner
            item {
                AccessStatusBanner(
                    accessStatus = accessStatus,
                    hardwareId = hardwareId,
                    onRechargeClick = { showTopUpDialog = true }
                )
            }

            // 3. Section Title: Choose a Pass
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "AVAILABLE SUBSCRIPTION PASSES",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "1 Point = ₹1 • Instant activation with Points",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Pass Cards (Daily, Weekly, Monthly)
            PassTier.entries.forEach { tier ->
                item(key = tier.id) {
                    PassCard(
                        passTier = tier,
                        pointsBalance = pointsBalance,
                        isProcessing = isProcessing,
                        onBuyClick = {
                            if (pointsBalance < tier.pointsCost) {
                                errorMessage = "Insufficient points! You need ${tier.pointsCost} points for ${tier.title}. Tap 'Recharge' to add points."
                            } else {
                                selectedPassForPurchase = tier
                            }
                        }
                    )
                }
            }

            // 5. Hardware Lock & Security Info
            item {
                HardwareSecurityCard(hardwareId = hardwareId)
            }
        }
    }

    // Top-Up Points Recharge Dialog
    if (showTopUpDialog) {
        TopUpPointsDialog(
            currentBalance = pointsBalance,
            onDismiss = { showTopUpDialog = false },
            onAddPoints = { amount ->
                coroutineScope.launch {
                    isProcessing = true
                    val result = LicenseManager.addPoints(amount)
                    isProcessing = false
                    showTopUpDialog = false
                    if (result.isSuccess) {
                        Toast.makeText(context, "Added $amount Points successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to add points: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    // Confirm Pass Purchase Dialog
    selectedPassForPurchase?.let { tier ->
        AlertDialog(
            onDismissRequest = { if (!isProcessing) selectedPassForPurchase = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    tint = PrimaryEmerald,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Confirm ${tier.title}",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Are you sure you want to purchase this pass using your Points Wallet?",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Pass Cost:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${tier.pointsCost} Points (₹${tier.priceInInr})", fontWeight = FontWeight.Bold)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Current Balance:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$pointsBalance Points", fontWeight = FontWeight.SemiBold)
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Balance After Purchase:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${pointsBalance - tier.pointsCost} Points", fontWeight = FontWeight.Bold, color = PrimaryEmerald)
                            }
                        }
                    }

                    Text(
                        text = "• Pass extends automatically if another pass is currently active.\n• Access works on any phone when logged in with this Gmail.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isProcessing = true
                            val res = LicenseManager.purchasePassWithPoints(tier)
                            isProcessing = false
                            selectedPassForPurchase = null
                            if (res.isSuccess) {
                                Toast.makeText(context, "${tier.title} Activated! Unlimited auto-accept enabled.", Toast.LENGTH_LONG).show()
                            } else {
                                errorMessage = res.exceptionOrNull()?.message ?: "Purchase failed."
                            }
                        }
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Activate Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedPassForPurchase = null },
                    enabled = !isProcessing
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error Alert Dialog
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(text = "Notice", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = msg, style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Button(
                    onClick = {
                        errorMessage = null
                        showTopUpDialog = true
                    }
                ) {
                    Text("Recharge Points")
                }
            },
            dismissButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("Close")
                }
            }
        )
    }
}

/**
 * 1. User Header & Points Wallet Card
 */
@Composable
private fun WalletHeaderCard(
    pointsBalance: Int,
    isLoggedIn: Boolean,
    userName: String?,
    userEmail: String?,
    onTopUp: () -> Unit,
    onSignIn: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subscription_wallet_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // User Gmail Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isLoggedIn) PrimaryEmerald.copy(alpha = 0.15f) else Color.White,
                        modifier = Modifier.size(42.dp),
                        shadowElevation = 1.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isLoggedIn) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = PrimaryEmerald,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_google_logo),
                                    contentDescription = "Google",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    Column {
                        Text(
                            text = if (isLoggedIn) (userName ?: "Captain Account") else "Guest Account",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isLoggedIn) (userEmail ?: "Cloud Sync Active") else "Log in with Gmail to sync passes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!isLoggedIn) {
                    FilledTonalButton(
                        onClick = onSignIn,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Sign In", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PrimaryEmerald.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "Cloud Synced",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF047857)
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Wallet Points Display & Top-up Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "POINTS WALLET BALANCE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$pointsBalance",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Points",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = AmberAccent
                            ),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(₹$pointsBalance Value)",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }

                Button(
                    onClick = onTopUp,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberAccent,
                        contentColor = Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                    modifier = Modifier.testTag("top_up_points_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add Points",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

/**
 * 2. Access Status & 2-Day Trial Banner
 */
@Composable
private fun AccessStatusBanner(
    accessStatus: AccessStatus,
    hardwareId: String,
    onRechargeClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "banner_pulse"
    )

    when (accessStatus) {
        is AccessStatus.PassActive -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("status_pass_active_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = PrimaryEmerald.copy(alpha = 0.12f)
                ),
                border = BorderStroke(1.5.dp, PrimaryEmerald.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = PrimaryEmerald,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.WorkspacePremium,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${accessStatus.passTier.title} Active",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PrimaryEmerald
                            ) {
                                Text(
                                    text = "VIP UNLOCKED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Expires in: ${accessStatus.formattedRemaining}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryEmeraldDark
                            )
                        )
                        Text(
                            text = "Valid until: " + SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(accessStatus.expiryTimestamp)),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        is AccessStatus.TrialActive -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(pulseScale)
                    .testTag("status_trial_active_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF0284C7).copy(alpha = 0.12f)
                ),
                border = BorderStroke(1.5.dp, Color(0xFF0284C7).copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF0284C7),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ElectricBolt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "2-Day Free Trial Active",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF0284C7)
                            ) {
                                Text(
                                    text = "FREE TRIAL",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Remaining: ${accessStatus.formattedRemaining}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0284C7)
                            )
                        )
                        Text(
                            text = "Hardware locked: $hardwareId (Full access unlocked)",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        is AccessStatus.Expired -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("status_expired_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                ),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (accessStatus.hasHadPreviousPass) "Subscription Expired" else "2-Day Free Trial Ended",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            )
                            Text(
                                text = "Automation clicks paused until a pass is activated.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Text(
                        text = accessStatus.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is AccessStatus.Loading -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Verifying cloud license & trial status...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * 3. Individual Subscription Pass Card
 */
@Composable
private fun PassCard(
    passTier: PassTier,
    pointsBalance: Int,
    isProcessing: Boolean,
    onBuyClick: () -> Unit
) {
    val isAffordable = pointsBalance >= passTier.pointsCost
    val isWeekly = passTier == PassTier.WEEKLY
    val isMonthly = passTier == PassTier.MONTHLY

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pass_card_${passTier.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = if (isWeekly) {
            BorderStroke(2.dp, AmberAccent.copy(alpha = 0.7f))
        } else if (isMonthly) {
            BorderStroke(2.dp, PrimaryEmerald.copy(alpha = 0.7f))
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isWeekly || isMonthly) 3.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = passTier.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            isWeekly -> AmberAccent.copy(alpha = 0.2f)
                            isMonthly -> PrimaryEmerald.copy(alpha = 0.2f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = passTier.tag,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isWeekly -> Color(0xFFB45309)
                                    isMonthly -> Color(0xFF047857)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // Price Badge
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "₹${passTier.priceInInr}",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Text(
                        text = "${passTier.pointsCost} Points",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = AmberAccent
                        )
                    )
                }
            }

            Text(
                text = passTier.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Features Checklist
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PassFeatureItem("Instant high-speed overlay automation clicks")
                PassFeatureItem("Surge radar & minimum fare filter evaluation")
                if (isWeekly || isMonthly) {
                    PassFeatureItem("High-priority voice text-to-speech alerts")
                }
                if (isMonthly) {
                    PassFeatureItem("Cross-device sync & hardware transfer support")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action Button
            Button(
                onClick = onBuyClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("buy_pass_${passTier.id}"),
                shape = RoundedCornerShape(12.dp),
                enabled = !isProcessing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAffordable) {
                        if (isMonthly) PrimaryEmerald else MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (isAffordable) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                if (isAffordable) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Buy with ${passTier.pointsCost} Points",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Need ${passTier.pointsCost - pointsBalance} More Pts (Tap to Buy)",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PassFeatureItem(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = PrimaryEmerald,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 4. Hardware ID & Security Info Card
 */
@Composable
private fun HardwareSecurityCard(hardwareId: String) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subscription_security_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = PrimaryEmerald,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Hardware Security & Cloud Sync",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            Text(
                text = "• 2-Day Free Trial is locked permanently to your physical hardware ID: $hardwareId. It cannot be reset by reinstalling or clearing app storage.\n• Paid passes & points are bound to your Gmail UID, allowing you to restore your subscription when switching devices.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 5. Top-Up Points Dialog
 */
@Composable
private fun TopUpPointsDialog(
    currentBalance: Int,
    onDismiss: () -> Unit,
    onAddPoints: (Int) -> Unit
) {
    val pointPacks = listOf(
        PointPack(points = 25, price = 25, bonusText = "Starter Pack"),
        PointPack(points = 50, price = 50, bonusText = "Weekly Pass Pack"),
        PointPack(points = 100, price = 100, bonusText = "+10 Bonus Pts!"),
        PointPack(points = 180, price = 179, bonusText = "Monthly Pass Pack")
    )

    var selectedPack by remember { mutableStateOf(pointPacks[1]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                tint = AmberAccent,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Recharge Points Wallet",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Choose a points bundle. 1 Point = ₹1. Points are deposited immediately into your cloud wallet.",
                    style = MaterialTheme.typography.bodyMedium
                )

                pointPacks.forEach { pack ->
                    val isSelected = selectedPack == pack
                    Surface(
                        onClick = { selectedPack = pack },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) AmberAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) AmberAccent else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${pack.points} Points",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = AmberAccent.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = pack.bonusText,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFB45309)
                                            ),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "₹${pack.price} via UPI / Instant Wallet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = AmberAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAddPoints(selectedPack.points) },
                colors = ButtonDefaults.buttonColors(containerColor = AmberAccent, contentColor = Color.Black)
            ) {
                Text("Recharge ${selectedPack.points} Pts (₹${selectedPack.price})", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class PointPack(
    val points: Int,
    val price: Int,
    val bonusText: String
)
