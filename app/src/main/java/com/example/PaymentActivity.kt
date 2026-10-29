package com.example

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pin
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CaptainAutoAcceptTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PaymentActivity - Manual UPI Payment & Secure Server UTR Verification Gateway.
 *
 * Architecture Flow:
 * 1. User selects Pass Tier (Daily ₹9, Weekly ₹49, Monthly ₹179).
 * 2. Launches UPI Intent (Google Pay / PhonePe / Paytm / BHIM) to Admin UPI ID.
 * 3. After paying, backend server receives bank confirmation and creates the verification record
 *    at: `received_payments/{UTR_Number}`.
 * 4. User manually inputs the 12-digit UTR and clicks "Verify Payment".
 * 5. Passes UTR to `PassManager.verifyPaymentWithBackendDetailed`:
 *    - Validates doc exists, status == "Verified", and isUsed == false.
 *    - Updates Firestore transaction (`isUsed = true`, `claimedBy`, `pass_expiry_date`).
 *    - Unlocks subscription pass upon verified server signal.
 */
class PaymentActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PASS_TIER_ID = "extra_pass_tier_id"
        private const val TAG = "PaymentActivity"

        const val FIRESTORE_COLLECTION_ADMIN_SETTINGS = "admin_settings"
        const val FIRESTORE_DOC_PAYMENT_CONFIG = "payment_config"

        /**
         * Fetches dynamic payment config (Admin UPI ID and Payee Name) from Firestore:
         * collection: `admin_settings`, document: `payment_config`
         */
        suspend fun fetchPaymentConfig(context: Context? = null): Pair<String, String> {
            return try {
                withTimeoutOrNull(3500L) {
                    val isInitialized = if (context != null) {
                        FirebaseApp.getApps(context).isNotEmpty()
                    } else {
                        try {
                            FirebaseApp.getInstance() != null
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (!isInitialized) {
                        Log.w(TAG, "FirebaseApp is not initialized; cannot fetch payment config.")
                        return@withTimeoutOrNull Pair("", "")
                    }
                    val firestore = FirebaseFirestore.getInstance()
                    val docSnapshot = firestore.collection(FIRESTORE_COLLECTION_ADMIN_SETTINGS)
                        .document(FIRESTORE_DOC_PAYMENT_CONFIG)
                        .get()
                        .await()

                    if (docSnapshot.exists()) {
                        val upi = docSnapshot.getString("adminUpiId")?.trim().orEmpty()
                        val payee = docSnapshot.getString("adminPayeeName")?.trim().orEmpty()
                        Log.d(TAG, "Successfully fetched payment config: upi=$upi, payee=$payee")
                        Pair(upi, payee)
                    } else {
                        Log.w(TAG, "Document admin_settings/payment_config does not exist.")
                        Pair("", "")
                    }
                } ?: run {
                    Log.w(TAG, "Fetching payment config timed out.")
                    Pair("", "")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch remote payment config from Firestore (${e.message}).")
                Pair("", "")
            }
        }
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

    fun onPaymentSuccess(paymentId: String) {
        PassManager.onPaymentSuccess(this@PaymentActivity, paymentId) { isSuccess ->
            if (isSuccess) {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    fun verifyPaymentWithBackend(paymentId: String, callback: (Boolean) -> Unit) {
        PassManager.verifyPaymentWithBackend(this@PaymentActivity, paymentId, callback)
    }
}

private enum class VerificationUiState {
    IDLE,
    VERIFYING,
    SUCCESS,
    NOT_FOUND,
    ALREADY_USED,
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

    var adminUpiId by remember { mutableStateOf("") }
    var adminPayeeName by remember { mutableStateOf("") }
    var isLoadingConfig by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val (fetchedUpi, fetchedName) = PaymentActivity.fetchPaymentConfig(context)
        adminUpiId = fetchedUpi
        adminPayeeName = fetchedName
        isLoadingConfig = false
    }

    val isPaymentConfigAvailable = !isLoadingConfig && adminUpiId.isNotBlank()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.payment_screen_title),
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
                            contentDescription = stringResource(R.string.payment_cd_back)
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
            PaymentHeaderBanner()

            // Step 1: Plan Selection Section
            PlanSelectionSection(
                selectedTier = selectedTier,
                onSelectTier = { tier ->
                    selectedTier = tier
                    uiState = VerificationUiState.IDLE
                    statusMessage = ""
                }
            )

            // Step 2: Pay via UPI Section
            UpiPaymentSection(
                selectedTier = selectedTier,
                adminUpiId = adminUpiId,
                adminPayeeName = adminPayeeName,
                isLoadingConfig = isLoadingConfig,
                isPaymentConfigAvailable = isPaymentConfigAvailable,
                clipboardManager = clipboardManager
            )

            // Step 3: Enter 12-Digit UTR Verification Section
            val verifyingMessage = stringResource(R.string.payment_verifying_msg)
            val successToastText = stringResource(R.string.payment_toast_success)
            UtrVerificationSection(
                utrInput = utrInput,
                onUtrChange = { clean ->
                    utrInput = clean
                    if (uiState != VerificationUiState.IDLE) {
                        uiState = VerificationUiState.IDLE
                        statusMessage = ""
                    }
                },
                uiState = uiState,
                clipboardManager = clipboardManager,
                focusManager = focusManager,
                onVerifyClick = {
                    focusManager.clearFocus()
                    uiState = VerificationUiState.VERIFYING
                    statusMessage = verifyingMessage

                    coroutineScope.launch {
                        val result = PassManager.verifyPaymentWithBackendDetailed(
                            context = context,
                            utr = utrInput,
                            preferredTier = selectedTier
                        )

                        when (result) {
                            is PassManager.VerificationResult.Success -> {
                                uiState = VerificationUiState.SUCCESS
                                statusMessage = context.getString(R.string.payment_success_msg, result.passTier.durationDays)
                                Toast.makeText(context, successToastText, Toast.LENGTH_SHORT).show()
                                delay(1500)
                                onSuccessDone()
                            }
                            is PassManager.VerificationResult.NotFound -> {
                                uiState = VerificationUiState.NOT_FOUND
                                statusMessage = result.message
                            }
                            is PassManager.VerificationResult.AlreadyUsed -> {
                                uiState = VerificationUiState.ALREADY_USED
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
                }
            )

            // Status & Feedback Card
            if (statusMessage.isNotEmpty() || uiState == VerificationUiState.VERIFYING) {
                StatusFeedbackCard(
                    uiState = uiState,
                    statusMessage = statusMessage
                )
            }

            // Reference Help Card
            UtrHelpSection()

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Modular Header Banner Component
 */
@Composable
private fun PaymentHeaderBanner(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.payment_header_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.payment_header_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * Modular Plan Selection Section Component
 */
@Composable
private fun PlanSelectionSection(
    selectedTier: PassTier,
    onSelectTier: (PassTier) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.payment_step_1_title),
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
                        .clickable { onSelectTier(tier) }
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
                            text = stringResource(R.string.payment_price_format, tier.priceInInr),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (tier.durationDays > 1)
                                stringResource(R.string.payment_duration_multi_day, tier.durationDays)
                            else
                                stringResource(R.string.payment_duration_single_day, tier.durationDays),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modular UPI Payment Section Component
 */
@Composable
private fun UpiPaymentSection(
    selectedTier: PassTier,
    adminUpiId: String,
    adminPayeeName: String,
    isLoadingConfig: Boolean,
    isPaymentConfigAvailable: Boolean,
    clipboardManager: ClipboardManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val defaultPayeeName = stringResource(R.string.payment_default_payee_name)

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.payment_step_2_title, selectedTier.priceInInr),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.payment_admin_upi_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // QR Code Container
            val paymentUpiUri = remember(adminUpiId, adminPayeeName, selectedTier) {
                if (adminUpiId.isNotBlank()) {
                    QrCodeGenerator.getUpiUriString(
                        vpa = adminUpiId,
                        name = adminPayeeName.ifBlank { defaultPayeeName },
                        amount = selectedTier.priceInInr.toDouble(),
                        note = "Pass ${selectedTier.title}"
                    )
                } else {
                    ""
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(14.dp)
                ) {
                    if (isLoadingConfig) {
                        Box(
                            modifier = Modifier
                                .size(175.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    } else if (adminUpiId.isBlank()) {
                        Box(
                            modifier = Modifier
                                .size(175.dp)
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.payment_btn_unavailable),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else {
                        val qrBitmap = remember(paymentUpiUri) {
                            if (paymentUpiUri.isNotBlank()) {
                                QrCodeGenerator.generateQrCodeBitmap(paymentUpiUri, 512, 512)
                            } else null
                        }
                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(175.dp)
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.payment_qr_cd_format, selectedTier.priceInInr),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            isLoadingConfig -> stringResource(R.string.payment_qr_fetching)
                            adminUpiId.isBlank() -> stringResource(R.string.payment_system_unavailable)
                            else -> stringResource(R.string.payment_qr_scan_caption)
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = if (!isLoadingConfig && adminUpiId.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // UPI ID Display Box with Copy Button
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
                    if (isLoadingConfig) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.payment_loading_upi_details),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (adminUpiId.isBlank()) {
                        Text(
                            text = stringResource(R.string.payment_system_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                    } else {
                        Column {
                            Text(
                                text = stringResource(R.string.payment_payee_label, adminPayeeName),
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
                    }

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(adminUpiId))
                            Toast.makeText(context, context.getString(R.string.payment_toast_upi_copied, adminUpiId), Toast.LENGTH_SHORT).show()
                        },
                        enabled = isPaymentConfigAvailable,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("copy_upi_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.payment_btn_copy))
                    }
                }
            }

            // Pay via UPI App Button
            Button(
                onClick = {
                    val act = context as? Activity
                    if (act != null) {
                        PassManager.initiateUpiPayment(
                            activity = act,
                            passTier = selectedTier,
                            payeeVpa = adminUpiId,
                            payeeName = adminPayeeName.ifBlank { defaultPayeeName },
                            onFailed = { reason ->
                                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                },
                enabled = isPaymentConfigAvailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("open_upi_app_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            ) {
                if (isLoadingConfig) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.payment_btn_checking_upi),
                        fontWeight = FontWeight.Bold
                    )
                } else if (adminUpiId.isBlank()) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.payment_btn_unavailable),
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.payment_btn_open_upi),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = stringResource(R.string.payment_upi_hint),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Modular UTR Verification Section Component
 */
@Composable
private fun UtrVerificationSection(
    utrInput: String,
    onUtrChange: (String) -> Unit,
    uiState: VerificationUiState,
    clipboardManager: ClipboardManager,
    focusManager: FocusManager,
    onVerifyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
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
                text = stringResource(R.string.payment_step_3_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = stringResource(R.string.payment_step_3_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = utrInput,
                onValueChange = { input ->
                    val clean = input.filter { it.isLetterOrDigit() }.take(16).uppercase()
                    onUtrChange(clean)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("utr_input_field"),
                enabled = uiState != VerificationUiState.VERIFYING,
                label = { Text(stringResource(R.string.payment_utr_label)) },
                placeholder = { Text(stringResource(R.string.payment_utr_placeholder)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Pin, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text ?: ""
                            val clean = clipText.filter { it.isLetterOrDigit() }.take(16).uppercase()
                            if (clean.isNotEmpty()) {
                                onUtrChange(clean)
                                Toast.makeText(context, context.getString(R.string.payment_toast_pasted, clean), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.payment_toast_no_clip), Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = uiState != VerificationUiState.VERIFYING,
                        modifier = Modifier.testTag("paste_utr_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = stringResource(R.string.payment_cd_paste)
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
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
                            text = if (utrInput.isNotEmpty() && utrInput.length < 9) {
                                stringResource(R.string.payment_utr_need_more, 9 - utrInput.length)
                            } else {
                                stringResource(R.string.payment_utr_hint)
                            },
                            color = if (utrInput.isNotEmpty() && utrInput.length < 9) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = stringResource(R.string.payment_char_count_format, utrInput.length),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                isError = utrInput.isNotEmpty() && utrInput.length < 9,
                shape = RoundedCornerShape(12.dp)
            )

            // Verify Payment Button
            Button(
                onClick = onVerifyClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("verify_payment_button"),
                enabled = utrInput.length in 9..16 && uiState != VerificationUiState.VERIFYING,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
            ) {
                if (uiState == VerificationUiState.VERIFYING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.payment_verifying_msg),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 2,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.payment_btn_verify),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * Modular Status Feedback Card Component
 */
@Composable
private fun StatusFeedbackCard(
    uiState: VerificationUiState,
    statusMessage: String,
    modifier: Modifier = Modifier
) {
    val (cardColor, contentColor, icon) = when (uiState) {
        VerificationUiState.VERIFYING -> Triple(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.HourglassTop
        )
        VerificationUiState.SUCCESS -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.CheckCircle
        )
        VerificationUiState.NOT_FOUND -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Default.HourglassTop
        )
        VerificationUiState.ALREADY_USED,
        VerificationUiState.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.ErrorOutline
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Info
        )
    }

    Card(
        modifier = modifier
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
                if (uiState == VerificationUiState.VERIFYING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = contentColor,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when (uiState) {
                        VerificationUiState.VERIFYING -> stringResource(R.string.payment_status_verifying_title)
                        VerificationUiState.SUCCESS -> stringResource(R.string.payment_status_success_title)
                        VerificationUiState.NOT_FOUND -> stringResource(R.string.payment_status_pending_title)
                        VerificationUiState.ALREADY_USED -> stringResource(R.string.payment_status_claimed_title)
                        VerificationUiState.ERROR -> stringResource(R.string.payment_status_notice_title)
                        else -> stringResource(R.string.payment_status_default_title)
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
                    text = stringResource(R.string.payment_tip_pending),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Modular UTR Help / Guide Component
 */
@Composable
private fun UtrHelpSection(
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.payment_help_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = stringResource(R.string.payment_help_desc),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
