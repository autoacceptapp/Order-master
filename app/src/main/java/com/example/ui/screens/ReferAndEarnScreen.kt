package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stars
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.LicenseManager
import com.example.ReferralManager
import com.example.auth.GoogleAuthManager
import com.example.ui.components.GoogleSignInBottomSheet
import kotlinx.coroutines.launch

private val WhatsAppColor = Color(0xFF25D366)
private val GoldAccent = Color(0xFFF59E0B)
private val EmeraldSuccess = Color(0xFF10B981)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferAndEarnScreen(
    onNavigateBack: () -> Unit = {},
    showTopBar: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    BackHandler {
        onNavigateBack()
    }

    // Reactive StateFlows from LicenseManager & GoogleAuthManager
    val userAuthState by GoogleAuthManager.userAuthState.collectAsState()
    val pointsBalance by LicenseManager.pointsBalance.collectAsState()
    val referralCode by LicenseManager.referralCode.collectAsState()
    val hasClaimedReferral by LicenseManager.hasClaimedReferral.collectAsState()
    val referralCount by LicenseManager.referralCount.collectAsState()
    val pendingInvite by ReferralManager.pendingInviteCode.collectAsState()

    var inputCode by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var copiedRecently by remember { mutableStateOf(false) }
    var showSignInSheet by remember { mutableStateOf(false) }

    // Auto-fill invite code if arrived from deep link
    LaunchedEffect(pendingInvite) {
        if (pendingInvite.isNotBlank() && inputCode.isBlank() && !hasClaimedReferral) {
            inputCode = pendingInvite
        }
    }

    if (showSignInSheet) {
        GoogleSignInBottomSheet(
            onDismissRequest = { showSignInSheet = false },
            onSignInSuccess = {
                showSignInSheet = false
                Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    val screenContent: @Composable (PaddingValues) -> Unit = { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Header Card: Welcome Bonus: 50 Pts & Referral Bonus: +20 Pts
            item {
                BonusHeaderCard(
                    pointsBalance = pointsBalance.toLong(),
                    hasClaimedWelcomeBonus = hasClaimedReferral
                )
            }

            // 2. User's Own Referral Code Card with Read-Only Field & Copy Button & Referral Count
            item {
                val displayCode = referralCode ?: if (userAuthState.isLoggedIn) "GENERATING..." else "SIGN IN"
                MyReferralCodeCard(
                    referralCode = displayCode,
                    referralCount = referralCount.toLong(),
                    isLoggedIn = userAuthState.isLoggedIn,
                    copiedRecently = copiedRecently,
                    onCopyCode = {
                        if (!referralCode.isNullOrBlank()) {
                            copyReferralCode(context, referralCode!!)
                            copiedRecently = true
                        } else if (!userAuthState.isLoggedIn) {
                            showSignInSheet = true
                        }
                    },
                    onShareGeneral = {
                        if (!referralCode.isNullOrBlank()) {
                            shareGeneral(context, referralCode!!)
                        } else if (!userAuthState.isLoggedIn) {
                            showSignInSheet = true
                        }
                    },
                    onShareWhatsApp = {
                        if (!referralCode.isNullOrBlank()) {
                            shareOnWhatsApp(context, referralCode!!)
                        } else if (!userAuthState.isLoggedIn) {
                            showSignInSheet = true
                        }
                    },
                    onSignInRequest = { showSignInSheet = true }
                )
            }

            // 3. Claim Referral Code Card (Disables input & button when hasClaimedReferral is true)
            item {
                EnterReferralCodeCard(
                    isLoggedIn = userAuthState.isLoggedIn,
                    hasClaimedReferral = hasClaimedReferral,
                    inputCode = inputCode,
                    onCodeChange = {
                        inputCode = it.uppercase()
                        if (errorMessage != null) errorMessage = null
                        if (successMessage != null) successMessage = null
                    },
                    isSubmitting = isSubmitting,
                    errorMessage = errorMessage,
                    successMessage = successMessage,
                    onSignInRequest = { showSignInSheet = true },
                    onSubmitCode = {
                        focusManager.clearFocus()
                        errorMessage = null
                        successMessage = null

                        if (!userAuthState.isLoggedIn) {
                            showSignInSheet = true
                            return@EnterReferralCodeCard
                        }

                        val trimmed = inputCode.trim()
                        if (trimmed.isBlank()) {
                            errorMessage = "Please enter a referral code."
                            return@EnterReferralCodeCard
                        }

                        if (trimmed.length != 8) {
                            errorMessage = "Referral code must be exactly 8 characters."
                            return@EnterReferralCodeCard
                        }

                        if (referralCode != null && trimmed.equals(referralCode, ignoreCase = true)) {
                            errorMessage = "Cannot use your own code"
                            return@EnterReferralCodeCard
                        }

                        if (hasClaimedReferral) {
                            errorMessage = "Bonus Already Claimed"
                            return@EnterReferralCodeCard
                        }

                        isSubmitting = true
                        coroutineScope.launch {
                            val result = ReferralManager.claimReferralCode(context, trimmed)
                            isSubmitting = false
                            result.onSuccess {
                                successMessage = "Referral applied! +50 Points added to your account."
                                inputCode = ""
                                ReferralManager.pendingInviteCode.value = ""
                                LicenseManager.notifyReferralClaimed()
                                Toast.makeText(context, "Claimed! +50 Points Added", Toast.LENGTH_LONG).show()
                            }.onFailure { err ->
                                val msg = err.message ?: "Failed to claim referral code."
                                errorMessage = msg
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }

            // 4. Referral Program Guide
            item {
                ReferralInstructionsCard()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showTopBar) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .testTag("refer_and_earn_screen"),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Refer & Earn",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("refer_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { paddingValues ->
            screenContent(paddingValues)
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("refer_and_earn_screen")
        ) {
            screenContent(PaddingValues(0.dp))
        }
    }
}

/**
 * 1. Bonus Header Card:
 * Displays "Welcome Bonus: 50 Pts" and "Referral Bonus: +20 Pts"
 */
@Composable
private fun BonusHeaderCard(
    pointsBalance: Long,
    hasClaimedWelcomeBonus: Boolean,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("referral_bonus_header_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
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
                        color = GoldAccent.copy(alpha = 0.2f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Paid,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Points Balance",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$pointsBalance PTS",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "1 PT = ₹1",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Welcome Bonus 50 Pts
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Welcome Bonus",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "50 Pts",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (hasClaimedWelcomeBonus) EmeraldSuccess else MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = if (hasClaimedWelcomeBonus) "Claimed" else "Available",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = if (hasClaimedWelcomeBonus) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Referral Bonus +20 Pts
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Referral Bonus",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "+20 Pts",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = GoldAccent
                            )
                        )
                        Text(
                            text = "Per Captain Invited",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * 2. User's Own Referral Code Card:
 * Displays user's referral code in read-only field with Copy button & referral count
 */
@Composable
private fun MyReferralCodeCard(
    referralCode: String,
    referralCount: Long,
    isLoggedIn: Boolean,
    copiedRecently: Boolean,
    onCopyCode: () -> Unit,
    onShareGeneral: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onSignInRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("my_referral_code_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Your Referral Code",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Share this code to earn +20 Points for every driver",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Referral Count Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "$referralCount Ref",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            if (!isLoggedIn) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Sign in to generate your referral code and earn free points.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onSignInRequest,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Sign in with Google")
                        }
                    }
                }
            } else {
                // Read-Only Referral Code Field with Copy Button
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "CODE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = referralCode,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 3.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        FilledTonalButton(
                            onClick = onCopyCode,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("copy_referral_code_button")
                        ) {
                            Icon(
                                imageVector = if (copiedRecently) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                                contentDescription = "Copy Code",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (copiedRecently) "Copied!" else "Copy")
                        }
                    }
                }

                // Share Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onShareGeneral,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("share_code_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Code",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Share Code",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    OutlinedButton(
                        onClick = onShareWhatsApp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("share_whatsapp_button"),
                        border = BorderStroke(1.dp, WhatsAppColor)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = "WhatsApp",
                            tint = WhatsAppColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 3. Claim Referral Code Card:
 * OutlinedTextField + Submit Button. Disables both when hasClaimedReferral is true.
 */
@Composable
private fun EnterReferralCodeCard(
    isLoggedIn: Boolean,
    hasClaimedReferral: Boolean,
    inputCode: String,
    onCodeChange: (String) -> Unit,
    isSubmitting: Boolean,
    errorMessage: String?,
    successMessage: String?,
    onSignInRequest: () -> Unit,
    onSubmitCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("enter_referral_code_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Claim Referral Code",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (hasClaimedReferral) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = EmeraldSuccess.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldSuccess,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "CLAIMED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = EmeraldSuccess
                                )
                            )
                        }
                    }
                }
            }

            if (!isLoggedIn) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Please sign in with Google to enter a referral code and claim your 50 points.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onSignInRequest,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Sign in with Google")
                        }
                    }
                }
            } else {
                Text(
                    text = if (hasClaimedReferral)
                        "You have already claimed your 50-point welcome bonus on this account."
                    else
                        "Enter an 8-digit referral code to claim 50 free points.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // OutlinedTextField for entering 8-digit referral code
                OutlinedTextField(
                    value = if (hasClaimedReferral) "BONUS CLAIMED" else inputCode,
                    onValueChange = onCodeChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("referral_code_input"),
                    placeholder = {
                        Text(
                            text = "e.g. OM123456",
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (hasClaimedReferral) Icons.Default.Lock else Icons.Default.CardGiftcard,
                            contentDescription = null,
                            tint = if (hasClaimedReferral) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                    },
                    singleLine = true,
                    enabled = !hasClaimedReferral && !isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (!hasClaimedReferral) onSubmitCode() }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                )

                // Error Message Notice
                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    errorMessage?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // Success Message Notice
                AnimatedVisibility(
                    visible = successMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    successMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = EmeraldSuccess.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldSuccess
                                ),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // Submit Button with Loading Indicator and "Bonus Already Claimed" when claimed
                Button(
                    onClick = onSubmitCode,
                    enabled = !hasClaimedReferral && !isSubmitting && inputCode.trim().length == 8,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("submit_referral_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Verifying...")
                    } else if (hasClaimedReferral) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Bonus Already Claimed",
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Submit",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 4. Explainer Guide
 */
@Composable
private fun ReferralInstructionsCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "How Refer & Earn Works",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "1",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column {
                    Text(
                        text = "Share Your Code",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Send your 8-digit referral code to other captains.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "2",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column {
                    Text(
                        text = "Both Get Rewarded",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Your referee receives 50 Welcome Points, and you receive +20 Referral Points.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Copies referral code to the system clipboard.
 */
private fun copyReferralCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Order Master Referral Code", code)
    clipboard?.setPrimaryClip(clip)
    Toast.makeText(context, "Referral Code '$code' copied to clipboard!", Toast.LENGTH_SHORT).show()
}

/**
 * Shares referral link via Android system share sheet.
 */
private fun shareGeneral(context: Context, code: String) {
    val inviteUrl = "https://ordermaster.app/invite?code=$code"
    val message = "Get 50 free points in Order Master! Use my invite code: $code or tap $inviteUrl"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Order Master Invite")
        putExtra(Intent.EXTRA_TEXT, message)
    }
    context.startActivity(Intent.createChooser(intent, "Share Invite Link via"))
}

/**
 * Shares referral link via WhatsApp.
 */
private fun shareOnWhatsApp(context: Context, code: String) {
    val inviteUrl = "https://ordermaster.app/invite?code=$code"
    val message = "Get 50 free points in Order Master! Use my invite code: $code or tap $inviteUrl"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        `package` = "com.whatsapp"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        shareGeneral(context, code)
    }
}
