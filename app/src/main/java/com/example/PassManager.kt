package com.example

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.example.data.PaymentVerificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * PassManager - Central Manager for Subscription Passes, Points Conversion,
 * and Payment Gateway Integrations.
 *
 * Pricing & Conversion (configured in PassPricingConfig):
 * - Daily Pass: ₹9 INR OR 100 Points (Validity: 24 Hours / 1 Day)
 * - Weekly Pass: ₹49 INR OR 500 Points (Validity: 7 Days)
 * - Monthly Pass: ₹179 INR OR 1500 Points (Validity: 28 Days)
 */
object PassManager {
    private const val TAG = "PassManager"

    const val PREFS_UTR_RATE_LIMIT = "utr_rate_limit_prefs"
    const val KEY_UTR_ATTEMPT_TIMESTAMPS = "utr_attempt_timestamps"
    const val MAX_UTR_ATTEMPTS_PER_MINUTE = 3
    const val UTR_RATE_LIMIT_WINDOW_MS = 60_000L

    private val _pendingOpenStore = MutableStateFlow(false)
    val pendingOpenStore: StateFlow<Boolean> = _pendingOpenStore.asStateFlow()

    fun requestOpenStore() {
        _pendingOpenStore.value = true
    }

    fun consumeOpenStoreRequest() {
        _pendingOpenStore.value = false
    }

    /**
     * Local client-side rate limit checking: allows a maximum of 3 verification attempts per minute.
     * Prevents spamming UTR verification calls to the backend.
     * Handles empty or corrupted SharedPreferences gracefully without crashing.
     */
    @Synchronized
    fun checkAndRecordUtrRateLimit(
        context: Context,
        now: Long = LicenseManager.getCurrentServerTimeMs()
    ): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_UTR_RATE_LIMIT, Context.MODE_PRIVATE)
            val raw = try {
                prefs.getString(KEY_UTR_ATTEMPT_TIMESTAMPS, "") ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "Corrupted UTR rate limit prefs; resetting: ${e.message}")
                ""
            }
            val timestamps = if (raw.isBlank()) {
                emptyList()
            } else {
                raw.split(",")
                    .mapNotNull { it.trim().toLongOrNull() }
            }
            val cutoff = now - UTR_RATE_LIMIT_WINDOW_MS
            val recentAttempts = timestamps.filter { it in (cutoff + 1)..now }
            if (recentAttempts.size >= MAX_UTR_ATTEMPTS_PER_MINUTE) {
                false
            } else {
                val updated = recentAttempts + now
                prefs.edit().putString(KEY_UTR_ATTEMPT_TIMESTAMPS, updated.joinToString(",")).commit()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating UTR rate limit; failing open gracefully: ${e.message}", e)
            true
        }
    }

    private val readOnlyPassScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun isStatusProOrTrialActive(status: AccessStatus): Boolean {
        return when (status) {
            is AccessStatus.PassActive -> status.passTier.isProPass && status.remainingTimeMs > 0
            is AccessStatus.TrialActive -> status.remainingTimeMs > 0
            else -> false
        }
    }

    /**
     * Strictly read-only StateFlow derived directly from [LicenseManager.accessStatus].
     * Emits true ONLY when a Pro Pass (`isProPass && remainingTimeMs > 0`) or Free Trial (`remainingTimeMs > 0`) is active.
     */
    val isPassActiveFlow: StateFlow<Boolean> = LicenseManager.accessStatus
        .map { status -> isStatusProOrTrialActive(status) }
        .distinctUntilChanged()
        .stateIn(
            scope = readOnlyPassScope,
            started = SharingStarted.Eagerly,
            initialValue = isStatusProOrTrialActive(LicenseManager.accessStatus.value)
        )

    /**
     * Checks strictly whether a subscription pass or trial is currently active.
     * Evaluates strictly from read-only [isPassActiveFlow] backed by [LicenseManager.accessStatus].
     * Zero local storage (no SharedPreferences).
     */
    fun isPassActive(context: Context? = null): Boolean {
        return isPassActiveFlow.value
    }

    /**
     * Checks if user access is granted based on active pass or trial status.
     * Evaluates strictly from in-memory StateFlow and LicenseManager.
     */
    fun isAccessGranted(context: Context): Boolean {
        return LicenseManager.isAccessGranted()
    }

    /**
     * Represents the detailed result of payment UTR verification.
     */
    sealed class VerificationResult {
        data class Success(val passTier: PassTier, val expiryTimestamp: Long) : VerificationResult()
        data class NotFound(val message: String = "Payment not verified yet. Please wait a minute or check your UTR") : VerificationResult()
        data class AlreadyUsed(val message: String = "This UTR has already been claimed.") : VerificationResult()
        data class Invalid(val message: String) : VerificationResult()
        data class Error(val message: String) : VerificationResult()
    }

    /**
     * Detailed verification of the user's inputted UTR string with Firestore.
     * Delegates to PaymentVerificationRepository.defaultInstance.verifyUtrDetailed to avoid duplicate implementations.
     */
    suspend fun verifyPaymentWithBackendDetailed(
        context: Context,
        utr: String,
        preferredTier: PassTier? = null
    ): VerificationResult {
        return PaymentVerificationRepository.defaultInstance.verifyUtrDetailed(
            context = context,
            utr = utr,
            preferredTier = preferredTier
        )
    }

    /**
     * Asynchronous suspend verification of UTR with Firestore backend.
     */
    suspend fun verifyPaymentWithBackendSuspend(
        context: Context,
        paymentId: String,
        preferredTier: PassTier? = null
    ): Boolean {
        return when (verifyPaymentWithBackendDetailed(context, paymentId, preferredTier)) {
            is VerificationResult.Success -> true
            else -> false
        }
    }

    /**
     * Checks if active network connectivity is available via centralized DeviceUtils.
     */
    fun isNetworkAvailable(context: Context): Boolean = DeviceUtils.isNetworkAvailable(context)

    /**
     * Server-Side / Expiry Date Check:
     * Delegates to PaymentVerificationRepository.defaultInstance.checkIsPaymentVerified and checks active trial fallback.
     */
    suspend fun verifyServerPassStatus(context: Context): Boolean = withContext(Dispatchers.IO) {
        val isPaidProActive = PaymentVerificationRepository.defaultInstance.checkIsPaymentVerified(context)
        val isTrialActive = (LicenseManager.accessStatus.value as? AccessStatus.TrialActive)?.let { it.remainingTimeMs > 0 } ?: false
        isPaidProActive || isTrialActive
    }

    /**
     * Returns active pass expiry timestamp strictly from LicenseManager in-memory server state.
     * Zero local storage.
     */
    fun getPassExpiryTimestamp(context: Context? = null): Long {
        return when (val status = LicenseManager.accessStatus.value) {
            is AccessStatus.PassActive -> if (status.passTier.isProPass) status.expiryTimestamp else 0L
            is AccessStatus.TrialActive -> status.expiryTimestamp
            else -> 0L
        }
    }

    /**
     * Activates a pass by deducting points from the user's wallet.
     * Evaluates that userPoints >= pass.pointsCost.
     */
    suspend fun activatePassWithPoints(context: Context, passTier: PassTier): Result<Unit> {
        val currentPoints = LicenseManager.pointsBalance.value
        val requiredPoints = passTier.effectivePointsCost()
        if (currentPoints < requiredPoints) {
            val needed = requiredPoints - currentPoints
            return Result.failure(
                IllegalStateException("Insufficient points. You need $requiredPoints points, but have $currentPoints ($needed more required).")
            )
        }

        val result = LicenseManager.purchasePassWithPoints(passTier)
        if (result.isSuccess) {
            val newExpiry = getPassExpiryTimestamp(context)
            AppSettings.addLog(
                title = "Pass Activated",
                message = "${passTier.title} activated with $requiredPoints Points. Valid for ${passTier.durationDays} day(s).",
                severity = LogSeverity.MATCH_ACCEPTED
            )
            Log.i(TAG, "Pass ${passTier.title} activated with points successfully on Firestore. Expiry: $newExpiry")
        }
        return result
    }

    /**
     * Activates a pass immediately upon direct payment (UPI or Payment Gateway).
     * Does NOT deduct points from the user's wallet balance.
     */
    suspend fun activatePassViaPayment(
        context: Context,
        passTier: PassTier,
        transactionId: String? = null
    ): Result<Unit> {
        val txn = transactionId ?: "UPI_TXN_${System.currentTimeMillis()}"
        val result = LicenseManager.activatePassViaPayment(passTier, txn)
        if (result.isSuccess) {
            AppSettings.addLog(
                title = "Pass Purchased via UPI",
                message = "${passTier.title} activated for ₹${passTier.effectivePriceInInr()} (Txn: $txn). Valid for ${passTier.durationDays} day(s).",
                severity = LogSeverity.MATCH_ACCEPTED
            )
            Log.i(TAG, "Pass ${passTier.title} activated via payment successfully.")
        }
        return result
    }

    /**
     * Overload for activating pass directly via LicenseManager Action Queue.
     */
    suspend fun activatePassViaPayment(
        passTier: PassTier,
        transactionId: String? = null
    ): Result<Unit> {
        return LicenseManager.activatePassViaPayment(passTier, transactionId)
    }

    /**
     * Constructs a standard Indian UPI payment URI according to NPCI specifications:
     * upi://pay?pa=<vpa>&pn=<name>&am=<amount>&cu=INR&tn=<note>&tr=<txnRef>
     */
    fun buildUpiUri(
        passTier: PassTier,
        payeeVpa: String,
        payeeName: String = "OrderMaster Captain Store",
        transactionRef: String = "ORDER_${passTier.id}_${System.currentTimeMillis()}"
    ): Uri {
        val formattedAmount = String.format(Locale.US, "%.2f", passTier.effectivePriceInInr().toDouble())
        return Uri.Builder()
            .scheme("upi")
            .authority("pay")
            .appendQueryParameter("pa", payeeVpa)
            .appendQueryParameter("pn", payeeName)
            .appendQueryParameter("tr", transactionRef)
            .appendQueryParameter("tn", "Pass ${passTier.title}")
            .appendQueryParameter("am", formattedAmount)
            .appendQueryParameter("cu", "INR")
            .build()
    }

    /**
     * Creates an Intent to launch UPI apps installed on the device.
     */
    fun createUpiPaymentIntent(
        passTier: PassTier,
        payeeVpa: String,
        payeeName: String = "OrderMaster Captain Store"
    ): Intent {
        val uri = buildUpiUri(passTier, payeeVpa = payeeVpa, payeeName = payeeName)
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /**
     * Known Indian UPI app packages for Android 11+ package resolution
     */
    val KNOWN_UPI_PACKAGES = listOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",                       // PhonePe
        "net.one97.paytm",                        // Paytm
        "in.org.npci.upiapp",                     // BHIM
        "com.dreamplug.androidapp",               // CRED
        "in.amazon.mShop.android.shopping",       // Amazon Pay
        "com.mobikwik_new",                       // MobiKwik
        "com.whatsapp",                           // WhatsApp
        "com.sbi.upi",                            // BHIM SBI Pay
        "com.axis.mobile",                        // Axis Mobile
        "com.bankofbaroda.upi",                   // bob World UPI
        "com.icicibank.pockets",                  // iMobile / Pockets
        "com.myairtelapp",                        // Airtel
        "com.freecharge.android",                 // Freecharge
        "com.jupiter.money",                      // Jupiter
        "com.fi.money",                           // Fi Money
        "com.paytmmall"
    )

    /**
     * Launches standard UPI Intent (Google Pay, PhonePe, Paytm, BHIM, etc.) for the user
     * to complete the payment.
     */
    fun initiateUpiPayment(
        activity: Activity,
        passTier: PassTier,
        payeeVpa: String,
        payeeName: String = "OrderMaster Captain Store",
        onSuccess: (txnId: String) -> Unit = {},
        onFailed: (reason: String) -> Unit = {}
    ) {
        if (payeeVpa.isBlank()) {
            onFailed("Payment UPI details are currently unavailable. Please try again later.")
            return
        }
        val pm = activity.packageManager
        val baseIntent = createUpiPaymentIntent(passTier, payeeVpa = payeeVpa, payeeName = payeeName)

        val installedPackages = KNOWN_UPI_PACKAGES.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }

        // Attempt 1: Try Intent Chooser
        try {
            val chooser = Intent.createChooser(baseIntent, "Pay ₹${passTier.effectivePriceInInr()} for ${passTier.title}")
            activity.startActivity(chooser)
            return
        } catch (e: Exception) {
            Log.d(TAG, "Chooser launch failed: ${e.message}, trying direct intent...")
        }

        // Attempt 2: Try Direct ACTION_VIEW Intent without Chooser
        try {
            activity.startActivity(baseIntent)
            return
        } catch (e: Exception) {
            Log.d(TAG, "Direct intent launch failed: ${e.message}, trying explicit packages...")
        }

        // Attempt 3: Try launching specific installed UPI apps directly
        for (pkg in installedPackages) {
            try {
                val directIntent = createUpiPaymentIntent(passTier, payeeVpa = payeeVpa, payeeName = payeeName).apply {
                    setPackage(pkg)
                }
                activity.startActivity(directIntent)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Failed launching explicit package $pkg: ${e.message}")
            }
        }

        // Attempt 4: Fallback - copy UPI ID to clipboard so user can pay manually
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("UPI ID", payeeVpa)
                clipboard.setPrimaryClip(clip)
            }
            Toast.makeText(
                activity,
                "UPI ID copied: $payeeVpa. Please open your UPI app to pay ₹${passTier.effectivePriceInInr()}.",
                Toast.LENGTH_LONG
            ).show()
            onFailed("UPI ID copied: $payeeVpa. Please pay in your UPI app and enter UTR.")
        } catch (e: Exception) {
            Log.w(TAG, "UPI intent launch and fallback failed: ${e.message}")
            Toast.makeText(
                activity,
                "Please pay to UPI ID: $payeeVpa",
                Toast.LENGTH_LONG
            ).show()
            onFailed("Please pay to UPI ID: $payeeVpa")
        }
    }

    /**
     * Opens the Captain Store Screen or Activity.
     */
    fun openStore(context: Context) {
        try {
            requestOpenStore()
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_STORE, true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open store in MainActivity: ${e.message}")
        }
    }

    /**
     * Checks if ads should be removed for the current user.
     * Returns true ONLY if an active Remove Ads Pass is held.
     */
    fun isAdsRemoved(): Boolean = LicenseManager.isAdsRemoved()
}
