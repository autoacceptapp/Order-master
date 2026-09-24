package com.example.ui.screens

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import com.example.QrCodeGenerator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.LicenseManager
import com.example.PassManager
import com.example.PassTier
import com.example.data.PaymentVerificationState
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.PrimaryEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PaymentVerificationScreen
 *
 * Automated UPI Payment Verification Screen via Firebase Firestore.
 * Handles:
 * - Scenario A: MacroDroid updated /received_payments/{utr} first (Instant Match).
 * - Scenario B: User entered UTR first (Real-time snapshot listener on /received_payments/{utr}).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentVerificationScreen(
    onNavigateBack: () -> Unit,
    onVerificationSuccess: () -> Unit = onNavigateBack,
    viewModel: PaymentVerificationViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    val uiState by viewModel.uiState.collectAsState()
    val utrInput by viewModel.utrInput.collectAsState()
    val selectedTier by viewModel.selectedTier.collectAsState()
    val isUtrValid by viewModel.isUtrValid.collectAsState()

    val merchantVpa = "autoaccept6122-1@okhdfcbank"
    val merchantName = "OrderMaster Captain Store"

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelPending()
        }
    }

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
                        onClick = {
                            viewModel.cancelPending()
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("verify_back_button")
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
                            text = "Verify UPI Payment",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Automated Bank SMS Verification",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PrimaryEmerald.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = PrimaryEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "MacroDroid Sync",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryEmerald,
                                    fontSize = 11.sp
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

            // 1. Success Banner / Card (if already verified successfully)
            if (uiState is PaymentVerificationState.Success) {
                val success = uiState as PaymentVerificationState.Success
                item {
                    VerificationSuccessCard(
                        successState = success,
                        onContinue = onVerificationSuccess
                    )
                }
            }

            // 2. Real-time Pending Banner (Scenario B)
            if (uiState is PaymentVerificationState.Pending) {
                val pending = uiState as PaymentVerificationState.Pending
                item {
                    PendingVerificationBanner(
                        pendingState = pending,
                        onCancel = { viewModel.cancelPending() }
                    )
                }
            }

            // 3. Error Banner (if error occurred)
            if (uiState is PaymentVerificationState.Error) {
                val err = uiState as PaymentVerificationState.Error
                item {
                    VerificationErrorCard(
                        errorState = err,
                        onRetry = { viewModel.submitVerification(context) },
                        onDismiss = { viewModel.resetState() }
                    )
                }
            }

            // 4. Pass Tier Selector Chip Row
            if (uiState !is PaymentVerificationState.Success) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "1. SELECT YOUR PURCHASED PLAN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PassTier.entries.forEach { tier ->
                                val isSelected = selectedTier == tier
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.onSelectTier(tier) },
                                    label = {
                                        Text(
                                            text = "${tier.title} (₹${tier.priceInInr})",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PrimaryEmerald.copy(alpha = 0.15f),
                                        selectedLabelColor = Color(0xFF047857),
                                        selectedLeadingIconColor = PrimaryEmerald
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // 5. Merchant QR Code & UPI Card
                item {
                    MerchantPaymentQrCard(
                        selectedTier = selectedTier,
                        merchantVpa = merchantVpa,
                        merchantName = merchantName,
                        onCopyVpa = {
                            clipboardManager.setText(AnnotatedString(merchantVpa))
                            Toast.makeText(context, "UPI ID copied: $merchantVpa", Toast.LENGTH_SHORT).show()
                        },
                        onOpenUpi = {
                            val act = context as? Activity
                            if (act != null) {
                                PassManager.initiateUpiPayment(
                                    activity = act,
                                    passTier = selectedTier,
                                    onSuccess = { txnId ->
                                        if (txnId.length == 12 && txnId.all { it.isDigit() }) {
                                            viewModel.onUtrChanged(txnId)
                                            viewModel.submitVerification(context)
                                        }
                                    },
                                    onFailed = { /* ignore cancellation */ }
                                )
                            }
                        }
                    )
                }

                // 6. 12-Digit UTR Input Card
                item {
                    UtrInputCard(
                        utrInput = utrInput,
                        isUtrValid = isUtrValid,
                        uiState = uiState,
                        onUtrChanged = { viewModel.onUtrChanged(it) },
                        onPaste = {
                            val sysClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = sysClipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (clipText.isNotBlank()) {
                                viewModel.onPasteFromClipboard(clipText)
                                Toast.makeText(context, "Pasted reference from clipboard", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onClear = { viewModel.onUtrChanged("") },
                        onSubmit = {
                            focusManager.clearFocus()
                            viewModel.submitVerification(context)
                        }
                    )
                }

                // 7. How Verification Works (Informational Guide)
                item {
                    VerificationExplainerCard()
                }
            }
        }
    }
}

/**
 * Scannable high-resolution UPI QR Code rendered dynamically from the UPI URI string.
 */
@Composable
fun ScannableUpiQrCode(
    upiUri: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "Scan UPI QR Code to pay"
) {
    val qrBitmap = remember(upiUri) {
        QrCodeGenerator.generateQrCodeBitmap(
            content = upiUri,
            widthPx = 512,
            heightPx = 512
        )
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            StylizedQrCodePlaceholder(
                modifier = Modifier.fillMaxSize(),
                centerBadgeText = "UPI"
            )
        }
    }
}

/**
 * Modern QR Code Placeholder Visual Component rendered with Canvas.
 */
@Composable
private fun StylizedQrCodePlaceholder(
    modifier: Modifier = Modifier,
    centerBadgeText: String = "UPI"
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val colorDark = Color(0xFF1E293B)
            val cornerBoxSize = w * 0.24f
            val cornerInnerSize = cornerBoxSize * 0.5f

            // Top-Left Finder Pattern
            drawRoundRect(
                color = colorDark,
                topLeft = Offset(0f, 0f),
                size = Size(cornerBoxSize, cornerBoxSize),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cornerBoxSize * 0.2f, cornerBoxSize * 0.2f),
                size = Size(cornerBoxSize * 0.6f, cornerBoxSize * 0.6f),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = colorDark,
                topLeft = Offset(cornerBoxSize * 0.35f, cornerBoxSize * 0.35f),
                size = Size(cornerInnerSize * 0.6f, cornerInnerSize * 0.6f),
                cornerRadius = CornerRadius(2f, 2f)
            )

            // Top-Right Finder Pattern
            drawRoundRect(
                color = colorDark,
                topLeft = Offset(w - cornerBoxSize, 0f),
                size = Size(cornerBoxSize, cornerBoxSize),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(w - cornerBoxSize + cornerBoxSize * 0.2f, cornerBoxSize * 0.2f),
                size = Size(cornerBoxSize * 0.6f, cornerBoxSize * 0.6f),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = colorDark,
                topLeft = Offset(w - cornerBoxSize + cornerBoxSize * 0.35f, cornerBoxSize * 0.35f),
                size = Size(cornerInnerSize * 0.6f, cornerInnerSize * 0.6f),
                cornerRadius = CornerRadius(2f, 2f)
            )

            // Bottom-Left Finder Pattern
            drawRoundRect(
                color = colorDark,
                topLeft = Offset(0f, h - cornerBoxSize),
                size = Size(cornerBoxSize, cornerBoxSize),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cornerBoxSize * 0.2f, h - cornerBoxSize + cornerBoxSize * 0.2f),
                size = Size(cornerBoxSize * 0.6f, cornerBoxSize * 0.6f),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = colorDark,
                topLeft = Offset(cornerBoxSize * 0.35f, h - cornerBoxSize + cornerBoxSize * 0.35f),
                size = Size(cornerInnerSize * 0.6f, cornerInnerSize * 0.6f),
                cornerRadius = CornerRadius(2f, 2f)
            )

            // Stylized QR Data Grid Elements
            val step = w / 16f
            val dotRadius = step * 0.38f

            // Pseudo-random deterministic module grid
            val patternSeeds = intArrayOf(
                0x5A, 0x3C, 0xA5, 0xC3, 0x96, 0x69, 0x55, 0xAA,
                0xF0, 0x0F, 0x33, 0xCC, 0x66, 0x99, 0x3A, 0x5C
            )

            for (row in 0..15) {
                val seed = patternSeeds[row % patternSeeds.size]
                for (col in 0..15) {
                    // Skip finder zones
                    val inTopLeft = row < 5 && col < 5
                    val inTopRight = row < 5 && col > 10
                    val inBottomLeft = row > 10 && col < 5
                    val inCenter = row in 6..9 && col in 6..9

                    if (!inTopLeft && !inTopRight && !inBottomLeft && !inCenter) {
                        val bit = (seed shr (col % 8)) and 1
                        if (bit == 1 || (row + col) % 3 == 0) {
                            drawCircle(
                                color = colorDark,
                                radius = dotRadius,
                                center = Offset(col * step + step / 2f, row * step + step / 2f)
                            )
                        }
                    }
                }
            }
        }

        // Center UPI Badge
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = PrimaryEmerald,
            shadowElevation = 2.dp,
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = centerBadgeText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}

/**
 * Merchant Payment QR & UPI Details Card
 */
@Composable
private fun MerchantPaymentQrCard(
    selectedTier: PassTier,
    merchantVpa: String,
    merchantName: String,
    onCopyVpa: () -> Unit,
    onOpenUpi: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("merchant_payment_card"),
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Amount & Pass Name
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Pay for ${selectedTier.title}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Validity: ${selectedTier.durationDays} Day${if (selectedTier.durationDays > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PrimaryEmerald.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "₹${selectedTier.priceInInr}",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = PrimaryEmerald
                        ),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            val upiUri = remember(merchantVpa, selectedTier) {
                QrCodeGenerator.getUpiUriString(
                    vpa = merchantVpa,
                    name = merchantName,
                    amount = selectedTier.priceInInr.toDouble(),
                    note = "Pass ${selectedTier.title}"
                )
            }

            // QR Code Container with subtle background
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    ScannableUpiQrCode(
                        upiUri = upiUri,
                        modifier = Modifier.size(190.dp),
                        contentDescription = "Scan to pay ₹${selectedTier.priceInInr} to $merchantVpa"
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Scan with any UPI App (GPay / PhonePe / Paytm / BHIM)",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Merchant VPA & Copy Button
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
                            text = "MERCHANT UPI ID",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = merchantVpa,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }

                    OutlinedButton(
                        onClick = onCopyVpa,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Direct Open UPI App Button
            Button(
                onClick = onOpenUpi,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("open_upi_app_button")
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Pay ₹${selectedTier.priceInInr} Directly in UPI App",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

/**
 * 12-Digit UTR Input Card with instant validation, paste affordance, and action button.
 */
@Composable
private fun UtrInputCard(
    utrInput: String,
    isUtrValid: Boolean,
    uiState: PaymentVerificationState,
    onUtrChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit
) {
    val isLoading = uiState is PaymentVerificationState.Loading
    val isPending = uiState is PaymentVerificationState.Pending

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("utr_input_card"),
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "2. ENTER 12-DIGIT UTR / REF NO",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "${utrInput.length} / 12 Digits",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (isUtrValid) PrimaryEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            // UTR Text Field
            OutlinedTextField(
                value = utrInput,
                onValueChange = onUtrChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("utr_text_field"),
                shape = RoundedCornerShape(14.dp),
                label = { Text("12-Digit Reference / UTR Number") },
                placeholder = { Text("e.g. 426719823451") },
                singleLine = true,
                enabled = !isLoading && !isPending,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = if (isUtrValid) ImeAction.Done else ImeAction.Default
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (isUtrValid) onSubmit()
                    }
                ),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        tint = if (isUtrValid) PrimaryEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (utrInput.isNotEmpty()) {
                            IconButton(onClick = onClear) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = onPaste) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = PrimaryEmerald
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isUtrValid) PrimaryEmerald else MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = if (isUtrValid) PrimaryEmerald.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline
                ),
                supportingText = {
                    Text(
                        text = if (isUtrValid) "✓ Valid 12-digit format ready to verify"
                        else "Found on GPay, PhonePe, Paytm, or bank SMS as 'UPI Ref / UTR No'",
                        color = if (isUtrValid) PrimaryEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            // Submit Button
            Button(
                onClick = onSubmit,
                enabled = isUtrValid && !isLoading && !isPending,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryEmerald,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("verify_utr_submit_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Querying Registry...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isUtrValid) "Verify & Unlock App Immediately" else "Enter 12 Digits to Verify",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

/**
 * Scenario B Pending Banner:
 * Active while waiting for MacroDroid automation to push the bank SMS.
 */
@Composable
private fun PendingVerificationBanner(
    pendingState: PaymentVerificationState.Pending,
    onCancel: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_verification_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = AmberAccent.copy(alpha = 0.12f)
        ),
        border = BorderStroke(1.5.dp, AmberAccent.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = AmberAccent,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.Black,
                            strokeWidth = 2.5.dp
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Waiting for Bank SMS",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AmberAccent
                        ) {
                            Text(
                                text = "LIVE LISTENER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.Black
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "UTR: ${pendingState.utr}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFFB45309)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "• Logged under /pending_verifications/${pendingState.utr}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• As soon as the merchant phone receives the bank SMS, MacroDroid will push to Firestore and this screen will unlock instantly.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB45309))
                ) {
                    Text("Cancel / Change UTR")
                }
            }
        }
    }
}

/**
 * Success Celebration Card when payment is verified and app is unlocked.
 */
@Composable
private fun VerificationSuccessCard(
    successState: PaymentVerificationState.Success,
    onContinue: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("verification_success_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = PrimaryEmerald.copy(alpha = 0.12f)
        ),
        border = BorderStroke(2.dp, PrimaryEmerald)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = PrimaryEmerald,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Text(
                text = "Payment Verified & App Unlocked!",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = PrimaryEmerald,
                textAlign = TextAlign.Center
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Active Pass Plan:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(successState.passTier.title, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Amount Verified:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹${successState.amount} INR", fontWeight = FontWeight.Bold, color = PrimaryEmerald)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Reference / UTR:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(successState.utr, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Verification Mode:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (successState.isImmediate) "Scenario A (Instant Match)" else "Scenario B (Realtime Listener)",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0284C7)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Access Valid Until:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val df = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                        Text(df.format(Date(successState.expiryTimestamp)), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Button(
                onClick = onContinue,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("continue_to_app_button")
            ) {
                Icon(
                    imageVector = Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Continue to Order Master", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Error Card with dismiss & retry options.
 */
@Composable
private fun VerificationErrorCard(
    errorState: PaymentVerificationState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("verification_error_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Verification Notice",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                )
            }

            Text(
                text = errorState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
                if (errorState.canRetry) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

/**
 * Explainer Card detailing how the verification architecture works.
 */
@Composable
private fun VerificationExplainerCard() {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("verification_explainer_card"),
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
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = PrimaryEmerald,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "How Automated Verification Works",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            Text(
                text = "1. Pay via any UPI app using the QR code or UPI ID above.\n" +
                        "2. Locate the 12-digit UTR / Ref Number in your UPI receipt (e.g. 426719823451).\n" +
                        "3. Enter the number here. MacroDroid automation automatically matches the bank SMS on our merchant phone.\n" +
                        "4. Once matched, the pass activates immediately without manual support.",
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
