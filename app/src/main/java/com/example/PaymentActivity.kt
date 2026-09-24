package com.example

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CaptainAutoAcceptTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PaymentActivity - Manual UPI Payment & MacroDroid UTR Verification Gateway.
 *
 * Architecture Flow:
 * 1. User selects Pass Tier (Daily ₹9, Weekly ₹49, Monthly ₹179).
 * 2. Launches UPI Intent (Google Pay / PhonePe / Paytm / BHIM) to Admin UPI ID.
 * 3. After paying, admin device receives bank SMS which MacroDroid intercepts,
 *    extracts the 12-digit UTR, and pushes to Firestore at: `received_payments/{UTR_Number}`.
 * 4. User manually inputs the 12-digit UTR and clicks "Verify Payment".
 * 5. Passes UTR to `PassManager.verifyPaymentWithBackendDetailed`:
 *    - Validates doc exists, status == "Verified", and isUsed == false.
 *    - Updates Firestore transaction (`isUsed = true`, `claimedBy`, `pass_expiry_date`).
 *    - Unlocks subscription pass upon verified server signal.
 */
class PaymentActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PASS_TIER_ID = "extra_pass_tier_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialTierId = intent.getStringExtra(EXTRA_PASS_TIER_ID)
        val initialTier = PassTier.fromId(initialTierId) ?: PassTier.DAILY

        setContent {
            CaptainAutoAcceptTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PaymentVerificationScreenContent(
                        initialTier = initialTier,
                        onBack = { finish() },
                        onSuccessDone = {
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                    )
                }
            }
        }
    }

    /**
     * Backward-compatible callback hook for external activities.
     */
    fun onPaymentSuccess(paymentId: String) {
        PassManager.onPaymentSuccess(this@PaymentActivity, paymentId) { isSuccess ->
            if (isSuccess) {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    /**
     * Verifies payment status directly with backend / Firebase Firestore
     */
    fun verifyPaymentWithBackend(paymentId: String, callback: (Boolean) -> Unit) {
        PassManager.verifyPaymentWithBackend(this@PaymentActivity, paymentId, callback)
    }
}

private enum class VerificationUiState {
    IDLE,
    VERIFYING,
    SUCCESS,
    NOT_FOUND,
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentVerificationScreenContent(
    initialTier: PassTier,
    onBack: () -> Unit,
    onSuccessDone: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    var selectedTier by remember { mutableStateOf(initialTier) }
    var utrInput by remember { mutableStateOf("") }
    var uiState by remember { mutableStateOf(VerificationUiState.IDLE) }
    var statusMessage by remember { mutableStateOf("") }

    val adminUpiId = "autoaccept6122-1@okhdfcbank"
    val adminPayeeName = "OrderMaster Captain Store"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "UPI Payment & Verification",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("payment_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Header Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "MacroDroid Automated Verification",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Pay via any UPI app, then submit the 12-digit UTR / UPI Ref number to unlock your pass.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Step 0: Plan Selection
            Text(
                text = "1. Select Pass Plan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PassTier.entries.forEach { tier ->
                    val isSelected = selectedTier == tier
                    OutlinedCard(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                selectedTier = tier
                                uiState = VerificationUiState.IDLE
                                statusMessage = ""
                            }
                            .testTag("tier_card_${tier.id}"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = tier.title.replace(" Pass", ""),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "₹${tier.priceInInr}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${tier.durationDays} Day${if (tier.durationDays > 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Step 1: Pay via UPI Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                        Text(
                            text = "2. Pay ₹${selectedTier.priceInInr} via UPI",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "Admin UPI ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // UPI ID display box with copy button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Payee: $adminPayeeName",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = adminUpiId,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(adminUpiId))
                                    Toast.makeText(context, "UPI ID copied: $adminUpiId", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("copy_upi_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy")
                            }
                        }
                    }

                    // Pay via UPI app button
                    Button(
                        onClick = {
                            val act = context as? Activity
                            if (act != null) {
                                PassManager.initiateUpiPayment(
                                    activity = act,
                                    passTier = selectedTier,
                                    onFailed = { reason ->
                                        Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("open_upi_app_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open UPI App (GPay / PhonePe / Paytm)",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "• Click to pay directly, or copy the UPI ID into your preferred payment app.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Step 2: Input 12-Digit UTR Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "3. Enter 12-Digit UTR / UPI Ref Number",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "After completing your payment, copy the 12-digit UTR / UPI Ref number from the payment receipt and enter it below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = utrInput,
                        onValueChange = { input ->
                            val digitsOnly = input.filter { it.isDigit() }.take(12)
                            utrInput = digitsOnly
                            if (uiState != VerificationUiState.IDLE) {
                                uiState = VerificationUiState.IDLE
                                statusMessage = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("utr_input_field"),
                        label = { Text("12-Digit UTR / UPI Ref No.") },
                        placeholder = { Text("e.g. 426719823451") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Pin, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text ?: ""
                                    val digits = clipText.filter { it.isDigit() }.take(12)
                                    if (digits.isNotEmpty()) {
                                        utrInput = digits
                                        Toast.makeText(context, "Pasted: $digits", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "No digits found in clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.testTag("paste_utr_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard"
                                )
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (utrInput.isNotEmpty() && utrInput.length < 12) {
                                        "Need 12 digits (${12 - utrInput.length} more)"
                                    } else {
                                        "Found on GPay/PhonePe receipt"
                                    },
                                    color = if (utrInput.isNotEmpty() && utrInput.length < 12) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = "${utrInput.length}/12",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        isError = utrInput.isNotEmpty() && utrInput.length < 12,
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Verify Payment Button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            uiState = VerificationUiState.VERIFYING
                            statusMessage = "Verifying with Firestore backend..."

                            coroutineScope.launch {
                                val result = PassManager.verifyPaymentWithBackendDetailed(
                                    context = context,
                                    utr = utrInput,
                                    preferredTier = selectedTier
                                )

                                when (result) {
                                    is PassManager.VerificationResult.Success -> {
                                        uiState = VerificationUiState.SUCCESS
                                        statusMessage = "Pass Activated! Valid for ${result.passTier.durationDays} day(s)."
                                        Toast.makeText(context, "✅ Pass Activated Successfully!", Toast.LENGTH_SHORT).show()
                                        delay(1500)
                                        onSuccessDone()
                                    }
                                    is PassManager.VerificationResult.NotFound -> {
                                        uiState = VerificationUiState.NOT_FOUND
                                        statusMessage = "Payment not verified yet. Please wait a minute or check your UTR"
                                    }
                                    is PassManager.VerificationResult.AlreadyUsed -> {
                                        uiState = VerificationUiState.ERROR
                                        statusMessage = result.message
                                    }
                                    is PassManager.VerificationResult.Invalid -> {
                                        uiState = VerificationUiState.ERROR
                                        statusMessage = result.message
                                    }
                                    is PassManager.VerificationResult.Error -> {
                                        uiState = VerificationUiState.ERROR
                                        statusMessage = result.message
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("verify_payment_button"),
                        enabled = utrInput.length == 12 && uiState != VerificationUiState.VERIFYING,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (uiState == VerificationUiState.VERIFYING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Checking received_payments/${utrInput}...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.Payments,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Verify Payment",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            // Status & Feedback Card
            if (statusMessage.isNotEmpty()) {
                val (cardColor, contentColor, icon) = when (uiState) {
                    VerificationUiState.SUCCESS -> Triple(
                        Color(0xFFE8F5E9),
                        Color(0xFF1B5E20),
                        Icons.Default.CheckCircle
                    )
                    VerificationUiState.NOT_FOUND -> Triple(
                        Color(0xFFFFF8E1),
                        Color(0xFFB78103),
                        Icons.Default.HourglassTop
                    )
                    VerificationUiState.ERROR -> Triple(
                        Color(0xFFFFEBEE),
                        Color(0xFFB71C1C),
                        Icons.Default.ErrorOutline
                    )
                    else -> Triple(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        Icons.Default.Info
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("verification_status_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = when (uiState) {
                                    VerificationUiState.SUCCESS -> "Verification Success"
                                    VerificationUiState.NOT_FOUND -> "Pending Verification"
                                    VerificationUiState.ERROR -> "Verification Notice"
                                    else -> "Status"
                                },
                                fontWeight = FontWeight.Bold,
                                color = contentColor,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor
                        )

                        if (uiState == VerificationUiState.NOT_FOUND) {
                            Text(
                                text = "Tip: MacroDroid SMS automation intercepts the bank SMS on the admin device and creates the record in seconds. If you just paid, please wait 30 to 60 seconds and tap 'Verify Payment' again.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = contentColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // Reference help card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Where to find the 12-digit UTR?",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "• Google Pay: Tap the payment > Look for 'UPI transaction ID'\n" +
                                "• PhonePe: View History > Payment Details > 'UTR'\n" +
                                "• Paytm: Passbook / Transaction History > 'UPI Ref No.'\n" +
                                "• Any Bank: Look for 12 digits starting with the current day/month sequence.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
