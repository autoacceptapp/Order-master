package com.example.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.AccessStatus
import com.example.DeviceUtils
import com.example.LicenseManager
import com.example.PassManager
import com.example.PassTier
import com.example.R
import com.example.auth.GoogleAuthManager
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.PrimaryEmerald

/**
 * SubscriptionScreen - Captain Store, Pass Management & Smart Dynamic Buttons.
 *
 * Requirements:
 * 1. Points Recharge completely removed (no top-up dialogs).
 * 2. Pricing & Conversion:
 *    - Daily: ₹9 INR OR 100 Points (24 Hours)
 *    - Weekly: ₹49 INR OR 500 Points (7 Days)
 *    - Monthly: ₹179 INR OR 1500 Points (28 Days)
 * 3. Smart Button UI Logic:
 *    - userPoints >= pass.pointsCost:
 *      * Primary: "Buy with X Points"
 *      * Secondary: "Or Pay ₹X via UPI"
 *    - userPoints < pass.pointsCost:
 *      * Primary: "Pay ₹X via UPI"
 *      * Status: "X Points Required (You have Y pts)"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onNavigateBack: () -> Unit,
    onTriggerGoogleSignIn: () -> Unit,
    onNavigateToPaymentVerification: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val pointsBalance by LicenseManager.pointsBalance.collectAsState()
    val userAuthState by GoogleAuthManager.userAuthState.collectAsState()

    var selectedPassForPointsPurchase by remember { mutableStateOf<PassTier?>(null) }
    var selectedPassForUpiPayment by remember { mutableStateOf<PassTier?>(null) }
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
                            text = "Points Conversion & UPI Licensing",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                },
                actions = {
                    // Points Balance Pill in TopAppBar (Read-only badge)
                    Surface(
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
            // 1. User Account & Wallet Summary Card
            item {
                WalletHeaderCard(
                    pointsBalance = pointsBalance,
                    isLoggedIn = userAuthState.isLoggedIn,
                    userName = userAuthState.userName,
                    userEmail = userAuthState.userEmail,
                    onSignIn = onTriggerGoogleSignIn
                )
            }

            // 2. Access / Trial Status Banner
            item {
                AccessStatusBanner(
                    accessStatus = accessStatus,
                    hardwareId = hardwareId
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
                            text = "Smart Conversion: Pay with Points or direct UPI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Pass Cards (Daily, Weekly, Monthly) with Smart Dynamic Buttons
            PassTier.entries.forEach { tier ->
                item(key = tier.id) {
                    PassCard(
                        passTier = tier,
                        pointsBalance = pointsBalance,
                        isProcessing = isProcessing,
                        onBuyWithPoints = {
                            if (pointsBalance >= tier.pointsCost) {
                                selectedPassForPointsPurchase = tier
                            }
                        },
                        onPayViaUpi = {
                            selectedPassForUpiPayment = tier
                        }
                    )
                }
            }

            // 5. Automated 12-Digit UTR Verification Card
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToPaymentVerification() }
                        .testTag("verify_utr_entry_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PrimaryEmerald.copy(alpha = 0.15f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.FlashOn,
                                    contentDescription = null,
                                    tint = PrimaryEmerald,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Already Paid via UPI?",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Submit 12-digit UTR for automated MacroDroid bank SMS verification",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = onNavigateToPaymentVerification,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Verify UTR", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            // 6. Hardware Lock & Security Info
            item {
                HardwareSecurityCard(hardwareId = hardwareId)
            }
        }
    }

    // Confirm Pass Purchase with Points Dialog
    selectedPassForPointsPurchase?.let { tier ->
        AlertDialog(
            onDismissRequest = { if (!isProcessing) selectedPassForPointsPurchase = null },
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
                        text = "Redeem your reward points to activate unlimited auto-acceptance:",
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
                                Text("${tier.pointsCost} Points", fontWeight = FontWeight.Bold, color = AmberAccent)
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
                                Text("Balance After:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${pointsBalance - tier.pointsCost} Points", fontWeight = FontWeight.Bold, color = PrimaryEmerald)
                            }
                        }
                    }

                    Text(
                        text = "• Active validity: ${tier.durationDays} Day(s)\n• Stacks onto any existing active pass\n• Instant cloud & local activation",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isProcessing = true
                        coroutineScope.launch {
                            val res = PassManager.activatePassWithPoints(context, tier)
                            isProcessing = false
                            selectedPassForPointsPurchase = null
                            if (res.isSuccess) {
                                Toast.makeText(context, "🎉 ${tier.title} Activated! Unlimited auto-accept enabled.", Toast.LENGTH_LONG).show()
                            } else {
                                errorMessage = res.exceptionOrNull()?.message ?: "Points activation failed."
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
                    Text("Redeem ${tier.pointsCost} Points", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedPassForPointsPurchase = null },
                    enabled = !isProcessing
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Direct UPI Payment Dialog
    selectedPassForUpiPayment?.let { tier ->
        UpiPaymentDialog(
            passTier = tier,
            isProcessing = isProcessing,
            onDismiss = { if (!isProcessing) selectedPassForUpiPayment = null },
            onNavigateToPaymentVerification = {
                selectedPassForUpiPayment = null
                onNavigateToPaymentVerification()
            },
            onLaunchUpi = {
                val act = context as? Activity
                if (act != null) {
                    PassManager.initiateUpiPayment(
                        activity = act,
                        passTier = tier,
                        onFailed = { reason ->
                            android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_LONG).show()
                        }
                    )
                    selectedPassForUpiPayment = null
                    val intent = android.content.Intent(context, com.example.PaymentActivity::class.java).apply {
                        putExtra(com.example.PaymentActivity.EXTRA_PASS_TIER_ID, tier.id)
                    }
                    context.startActivity(intent)
                } else {
                    val intent = android.content.Intent(context, com.example.PaymentActivity::class.java).apply {
                        putExtra(com.example.PaymentActivity.EXTRA_PASS_TIER_ID, tier.id)
                    }
                    context.startActivity(intent)
                    selectedPassForUpiPayment = null
                }
            },
            onSimulateSuccess = {
                // Removed simulation: Redirect directly to PaymentActivity for real UTR verification
                selectedPassForUpiPayment = null
                val intent = android.content.Intent(context, com.example.PaymentActivity::class.java).apply {
                    putExtra(com.example.PaymentActivity.EXTRA_PASS_TIER_ID, tier.id)
                }
                context.startActivity(intent)
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
                Button(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * 1. User Header & Points Wallet Status Card (Recharge option removed)
 */
@Composable
private fun WalletHeaderCard(
    pointsBalance: Int,
    isLoggedIn: Boolean,
    userName: String?,
    userEmail: String?,
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

            // Wallet Points Display & System Rewards Information
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
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AmberAccent.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.35f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CardGiftcard,
                            contentDescription = null,
                            tint = Color(0xFFB45309),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Referrals & Rewards",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB45309)
                            )
                        )
                    }
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
    hardwareId: String
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
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Active Pass: ${accessStatus.passTier.title}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PrimaryEmerald
                            ) {
                                Text(
                                    text = "ACTIVE",
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
                                color = PrimaryEmerald
                            )
                        )
                        Text(
                            text = "Auto-accept is active with zero points deduction per ride.",
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
                                text = if (accessStatus.hasHadPreviousPass) "Subscription Pass Expired" else "2-Day Free Trial Ended",
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
                    Text("Verifying cloud license & pass status...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * 3. Individual Subscription Pass Card with SMART DYNAMIC ACTION BUTTONS
 */
@Composable
private fun PassCard(
    passTier: PassTier,
    pointsBalance: Int,
    isProcessing: Boolean,
    onBuyWithPoints: () -> Unit,
    onPayViaUpi: () -> Unit
) {
    val hasEnoughPoints = pointsBalance >= passTier.pointsCost
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
            // Header Row: Title, Tag, and Price
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

                // Dual Price Display: INR & Points
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
                PassFeatureItem("Unlimited high-speed auto-acceptance (${passTier.durationDays} Day${if (passTier.durationDays > 1) "s" else ""})")
                PassFeatureItem("Zero point deduction per accepted ride")
                PassFeatureItem("Surge radar & minimum fare filter evaluation")
                if (isWeekly || isMonthly) {
                    PassFeatureItem("High-priority voice text-to-speech announcer")
                }
                if (isMonthly) {
                    PassFeatureItem("Cross-device sync & hardware transfer support")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // SMART DYNAMIC BUTTONS UI
            if (hasEnoughPoints) {
                // CASE 1: User has sufficient Points balance
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Primary Action: Buy with Points
                    Button(
                        onClick = onBuyWithPoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("buy_points_${passTier.id}"),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMonthly) PrimaryEmerald else MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
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
                    }

                    // Secondary Action: Or Pay ₹X via UPI
                    OutlinedButton(
                        onClick = onPayViaUpi,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("pay_upi_alt_${passTier.id}"),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isProcessing,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Payment,
                            contentDescription = null,
                            tint = PrimaryEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Or Pay ₹${passTier.priceInInr} via UPI",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            } else {
                // CASE 2: User has insufficient Points balance
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Primary Action: Pay via UPI
                    Button(
                        onClick = onPayViaUpi,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("pay_upi_${passTier.id}"),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryEmerald,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Pay ₹${passTier.priceInInr} via UPI",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    // Status Information: Points Required
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("points_status_${passTier.id}"),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${passTier.pointsCost} Points Required (You have $pointsBalance pts)",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
 * 4. UPI Payment Dialog with native UPI intent trigger & Sandbox instant test option
 */
@Composable
private fun UpiPaymentDialog(
    passTier: PassTier,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
    onNavigateToPaymentVerification: () -> Unit,
    onLaunchUpi: () -> Unit,
    onSimulateSuccess: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = null,
                tint = PrimaryEmerald,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Pay ₹${passTier.priceInInr} via UPI",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Activate ${passTier.title} (${passTier.durationDays} Days) directly through your preferred UPI app.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
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
                            Text("Pass Plan:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(passTier.title, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Amount to Pay:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹${passTier.priceInInr} INR", fontWeight = FontWeight.ExtraBold, color = PrimaryEmerald)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Validity:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${passTier.durationDays} Day(s)", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Text(
                    text = "Supported: Google Pay, PhonePe, Paytm, BHIM, and any Indian bank UPI app.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onLaunchUpi,
                enabled = !isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Open UPI App", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onNavigateToPaymentVerification,
                    enabled = !isProcessing
                ) {
                    Text("Enter UTR", color = PrimaryEmerald, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss, enabled = !isProcessing) {
                    Text("Cancel")
                }
            }
        }
    )
}

/**
 * 5. Hardware ID & Security Info Card
 */
@Composable
private fun HardwareSecurityCard(hardwareId: String) {
    val clipboardManager = LocalClipboardManager.current

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subscription_security_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
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
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = PrimaryEmerald,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Hardware Lock & Anti-Reset Policy",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            Text(
                text = "Each physical phone receives exactly one 2-day free trial locked to its hardware signature. Reinstalling or clearing data does not grant additional trials. Subscriptions sync automatically to your Gmail account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "DEVICE HARDWARE ID",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = hardwareId,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(hardwareId))
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Hardware ID",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
