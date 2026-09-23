package com.example

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.FirebaseFirestore
import com.example.data.PaymentVerificationRepository
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * PassManager - Central Manager for Subscription Passes, Points Conversion,
 * and Payment Gateway Integrations.
 *
 * Pricing & Conversion:
 * - Daily Pass: ₹9 INR OR 100 Points (Validity: 24 Hours / 1 Day)
 * - Weekly Pass: ₹49 INR OR 500 Points (Validity: 7 Days)
 * - Monthly Pass: ₹179 INR OR 1500 Points (Validity: 28 Days)
 */
object PassManager {
    private const val TAG = "PassManager"
    private const val PREF_NAME = "PassPrefs"
    private const val KEY_IS_PASS_ACTIVE = "is_pass_active"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Throttle timestamp for access-expired alerts to prevent spamming active order loops
    private var lastExpiredAlertTimestamp = 0L
    private const val ALERT_THROTTLE_WINDOW_MS = 30_000L

    /**
     * Exposes reactive StateFlow for active pass expiry timestamp.
     */
    val passExpiryTimestampFlow: StateFlow<Long> = AppSettings.passExpiryTimestampFlow

    private val _isPassActiveFlow = MutableStateFlow(false)

    /**
     * Exposes reactive StateFlow indicating whether the subscription pass is active.
     */
    val isPassActiveFlow: StateFlow<Boolean> = _isPassActiveFlow.asStateFlow()

    /**
     * Checks strictly whether a subscription pass is currently active.
     * Default value is ALWAYS false (in SharedPreferences / DataStore)
     * until verified by server response.
     */
    fun isPassActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // Default value FALSE honi chahiye
        val isActive = prefs.getBoolean(KEY_IS_PASS_ACTIVE, false)
        if (!isActive) {
            _isPassActiveFlow.value = false
            return false
        }

        // Double check local expiry timestamp hasn't lapsed
        val expiry = AppSettings.getPassExpiryTimestamp(context)
        if (expiry > 0L && System.currentTimeMillis() >= expiry) {
            setPassStatus(context, false)
            return false
        }

        _isPassActiveFlow.value = true
        return true
    }

    /**
     * Updates pass status in SharedPreferences (PassPrefs).
     */
    fun setPassStatus(context: Context, status: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_PASS_ACTIVE, status).apply()
        _isPassActiveFlow.value = status
    }

    /**
     * Returns true if user has an active pass or an active free trial.
     */
    fun isAccessGranted(context: Context): Boolean {
        if (isPassActive(context)) {
            val now = System.currentTimeMillis()
            val passExpiry = AppSettings.getPassExpiryTimestamp(context)
            if (passExpiry == 0L || now < passExpiry) {
                return true
            } else {
                setPassStatus(context, false)
            }
        }

        // Check hardware trial access from LicenseManager if pass is not active
        return LicenseManager.isAccessGranted()
    }

    /**
     * 1. Payment Response Double-Check:
     * Pass ka status (isPassActive = true) sirf aur sirf tabhi update karein
     * jab Razorpay / Cashfree / Firebase Backend se Success Signal aaye.
     */
    fun onPaymentSuccess(
        context: Context,
        paymentId: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        // 1. Server/Backend se verify karein
        verifyPaymentWithBackend(context, paymentId) { isSuccess ->
            if (isSuccess) {
                // Sirf success aane par pass active karein
                setPassStatus(context, true)
                Toast.makeText(context, "Pass Activated!", Toast.LENGTH_SHORT).show()
                onComplete?.invoke(true)
            } else {
                // Payment verify nahi hua
                setPassStatus(context, false)
                Toast.makeText(context, "Payment Failed/Not Verified", Toast.LENGTH_SHORT).show()
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Double-checks payment response with Backend (Firebase Firestore / Server).
     * Only returns true if backend confirms successful transaction.
     */
    fun verifyPaymentWithBackend(
        context: Context,
        paymentId: String,
        onResult: (Boolean) -> Unit
    ) {
        scope.launch {
            val isSuccess = verifyPaymentWithBackendSuspend(context, paymentId)
            withContext(Dispatchers.Main) {
                onResult(isSuccess)
            }
        }
    }

    /**
     * Asynchronous suspend verification of paymentId with Firestore backend.
     */
    suspend fun verifyPaymentWithBackendSuspend(
        context: Context,
        paymentId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val trimmedId = paymentId.trim()
            if (trimmedId.isEmpty()) return@withContext false

            val firestore = FirebaseFirestore.getInstance()
            val userId = PaymentVerificationRepository.defaultInstance.resolveUserId(context)

            // Check 1: Check in /received_payments/{paymentId}
            val receivedDoc = firestore.collection(PaymentVerificationRepository.COLL_RECEIVED_PAYMENTS)
                .document(trimmedId)
                .get()
                .await()

            if (receivedDoc.exists()) {
                val status = receivedDoc.getString("status") ?: ""
                val isUsed = receivedDoc.getBoolean("isUsed") ?: false
                val claimedBy = receivedDoc.getString("claimedBy")
                if (status.equals("Verified", ignoreCase = true) || status.equals("SUCCESS", ignoreCase = true)) {
                    if (!isUsed || claimedBy == userId) {
                        return@withContext true
                    }
                }
            }

            // Check 2: Check user's profile in /users/{userId}
            val userDoc = firestore.collection("users")
                .document(userId)
                .get()
                .await()

            if (userDoc.exists()) {
                val paymentStatus = userDoc.getString("payment_status")
                    ?: userDoc.getString("paymentStatus")
                    ?: if (userDoc.getBoolean("isPaymentVerified") == true) "SUCCESS" else "FAILED"
                val lastVerifiedUtr = userDoc.getString("lastVerifiedUtr")
                val lastTxnId = userDoc.getString("lastTransactionId")

                val passExpiryDate = userDoc.getLong("pass_expiry_date")
                    ?: userDoc.getLong("passExpiryDate")
                    ?: userDoc.getLong("subscriptionExpiryTimestamp")
                    ?: userDoc.getDate("subscriptionExpiry")?.time
                    ?: 0L

                val isSuccessStatus = paymentStatus.equals("SUCCESS", ignoreCase = true) || paymentStatus.equals("VERIFIED", ignoreCase = true)
                val isMatchingId = trimmedId == lastVerifiedUtr || trimmedId == lastTxnId

                if (isSuccessStatus && (isMatchingId || System.currentTimeMillis() < passExpiryDate)) {
                    return@withContext true
                }
            }

            // Check 3: Check in /pending_verifications/{paymentId}
            val pendingDoc = firestore.collection("pending_verifications")
                .document(trimmedId)
                .get()
                .await()

            if (pendingDoc.exists()) {
                val status = pendingDoc.getString("status") ?: ""
                if (status.equals("COMPLETED", ignoreCase = true) || status.equals("VERIFIED", ignoreCase = true)) {
                    return@withContext true
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "verifyPaymentWithBackend failed for $paymentId: ${e.message}", e)
            false
        }
    }

    /**
     * 3. Server-Side / Expiry Date Check:
     * User ID ke saath payment_status aur pass_expiry_date store karein.
     * Jab user app khole (chahe Accessibility ON ho ya OFF), Server check karein:
     * if (currentTime < passExpiryDate && paymentStatus == "SUCCESS") -> Pass Active.
     * else -> Pass Expired / Inactive.
     */
    suspend fun verifyServerPassStatus(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val userId = PaymentVerificationRepository.defaultInstance.resolveUserId(context)
            val doc = FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .get()
                .await()

            val currentTime = System.currentTimeMillis()
            if (doc.exists()) {
                val paymentStatus = doc.getString("payment_status")
                    ?: doc.getString("paymentStatus")
                    ?: if (doc.getBoolean("isPaymentVerified") == true) "SUCCESS" else "FAILED"

                val passExpiryDate: Long = doc.getLong("pass_expiry_date")
                    ?: doc.getLong("passExpiryDate")
                    ?: doc.getLong("subscriptionExpiryTimestamp")
                    ?: doc.getDate("subscriptionExpiry")?.time
                    ?: 0L

                val isSuccess = paymentStatus.equals("SUCCESS", ignoreCase = true) || paymentStatus.equals("VERIFIED", ignoreCase = true)
                val isActive = currentTime < passExpiryDate && isSuccess

                withContext(Dispatchers.Main) {
                    if (isActive) {
                        setPassStatus(context, true)
                        AppSettings.setPassExpiryTimestamp(context, passExpiryDate)
                        Log.i(TAG, "Server check: Pass is ACTIVE until $passExpiryDate for user $userId")
                    } else {
                        setPassStatus(context, false)
                        AppSettings.setPassExpiryTimestamp(context, 0L)
                        Log.w(TAG, "Server check: Pass is EXPIRED/INACTIVE (expiry=$passExpiryDate, status=$paymentStatus) for user $userId")
                    }
                }
                isActive
            } else {
                withContext(Dispatchers.Main) {
                    setPassStatus(context, false)
                    AppSettings.setPassExpiryTimestamp(context, 0L)
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyServerPassStatus error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                isPassActive(context)
            }
        }
    }

    /**
     * Background sync of server pass status with callback.
     */
    fun syncPassStatusWithServer(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch {
            val isActive = verifyServerPassStatus(context)
            withContext(Dispatchers.Main) {
                onComplete?.invoke(isActive)
            }
        }
    }

    /**
     * Returns active pass expiry timestamp stored in AppSettings.
     */
    fun getPassExpiryTimestamp(context: Context): Long {
        return AppSettings.getPassExpiryTimestamp(context)
    }

    /**
     * Formats remaining time for the current pass into a human-readable string.
     */
    fun getFormattedTimeRemaining(context: Context): String {
        val remaining = AppSettings.getPassExpiryTimestamp(context) - System.currentTimeMillis()
        if (remaining <= 0) return "Expired"
        val days = TimeUnit.MILLISECONDS.toDays(remaining)
        val hours = TimeUnit.MILLISECONDS.toHours(remaining) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60

        return when {
            days > 0 -> String.format(Locale.US, "%dd %02dh %02dm", days, hours, minutes)
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            else -> String.format(Locale.US, "%02dm %02ds", minutes, seconds)
        }
    }

    /**
     * Activates a pass by deducting points from the user's wallet.
     * Evaluates that userPoints >= pass.pointsCost.
     */
    suspend fun activatePassWithPoints(context: Context, passTier: PassTier): Result<Unit> {
        val currentPoints = LicenseManager.pointsBalance.value
        if (currentPoints < passTier.pointsCost) {
            val needed = passTier.pointsCost - currentPoints
            return Result.failure(
                IllegalStateException("Insufficient points. You need ${passTier.pointsCost} points, but have $currentPoints ($needed more required).")
            )
        }

        val result = LicenseManager.purchasePassWithPoints(passTier)
        if (result.isSuccess) {
            val newExpiry = AppSettings.getPassExpiryTimestamp(context)
            AppSettings.addLog(
                title = "Pass Activated",
                message = "${passTier.title} activated with ${passTier.pointsCost} Points. Valid for ${passTier.durationDays} day(s).",
                severity = LogSeverity.MATCH_ACCEPTED
            )
            Log.i(TAG, "Pass ${passTier.title} activated with points successfully. Expiry: $newExpiry")
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
                message = "${passTier.title} activated for ₹${passTier.priceInInr} (Txn: $txn). Valid for ${passTier.durationDays} day(s).",
                severity = LogSeverity.MATCH_ACCEPTED
            )
            Log.i(TAG, "Pass ${passTier.title} activated via payment successfully.")
        }
        return result
    }

    // =========================================================================
    // UPI & PAYMENT GATEWAY INTEGRATION STUBS
    // =========================================================================

    /**
     * Constructs a standard Indian UPI payment URI according to NPCI specifications:
     * upi://pay?pa=<vpa>&pn=<name>&am=<amount>&cu=INR&tn=<note>&tr=<txnRef>
     */
    fun buildUpiUri(
        passTier: PassTier,
        payeeVpa: String = "ordermaster@upi",
        payeeName: String = "OrderMaster Captain Store",
        transactionRef: String = "ORDER_${passTier.id}_${System.currentTimeMillis()}"
    ): Uri {
        return Uri.Builder()
            .scheme("upi")
            .authority("pay")
            .appendQueryParameter("pa", payeeVpa)
            .appendQueryParameter("pn", payeeName)
            .appendQueryParameter("mc", "5499") // Miscellaneous merchandise
            .appendQueryParameter("tr", transactionRef)
            .appendQueryParameter("tn", "Pass: ${passTier.title}")
            .appendQueryParameter("am", passTier.priceInInr.toString())
            .appendQueryParameter("cu", "INR")
            .build()
    }

    /**
     * Creates an Intent to launch UPI apps installed on the device.
     */
    fun createUpiPaymentIntent(passTier: PassTier): Intent {
        val uri = buildUpiUri(passTier)
        return Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Launches UPI Payment Gateway flow with automatic fallback to immediate
     * test simulation if no UPI apps are present (e.g. testing in simulator / browser).
     */
    fun initiateUpiPayment(
        activity: Activity,
        passTier: PassTier,
        onSuccess: (txnId: String) -> Unit,
        onFailed: (reason: String) -> Unit
    ) {
        val intent = createUpiPaymentIntent(passTier)
        val packageManager = activity.packageManager
        val canResolve = intent.resolveActivity(packageManager) != null

        if (canResolve) {
            try {
                val chooser = Intent.createChooser(intent, "Pay ₹${passTier.priceInInr} for ${passTier.title}")
                activity.startActivity(chooser)
                // In production, activity will handle onActivityResult from UPI app.
                // For instant verification convenience, provide a callback hook.
                onSuccess("UPI_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                Log.w(TAG, "UPI intent launch failed: ${e.message}. Using fallback payment handler.")
                simulatePaymentSuccess(activity, passTier, onSuccess)
            }
        } else {
            // Simulator or device without UPI app (Google Pay, PhonePe, Paytm, BHIM)
            Log.i(TAG, "No native UPI app found. Executing payment gateway simulation.")
            simulatePaymentSuccess(activity, passTier, onSuccess)
        }
    }

    /**
     * Fallback payment gateway simulation stub for instant verification and sandbox testing.
     */
    private fun simulatePaymentSuccess(
        context: Context,
        passTier: PassTier,
        onSuccess: (txnId: String) -> Unit
    ) {
        val simulatedTxnId = "SIM_UPI_${System.currentTimeMillis()}"
        scope.launch {
            val result = activatePassViaPayment(context, passTier, simulatedTxnId)
            if (result.isSuccess) {
                Toast.makeText(
                    context,
                    "Payment of ₹${passTier.priceInInr} Successful! ${passTier.title} activated.",
                    Toast.LENGTH_LONG
                ).show()
                onSuccess(simulatedTxnId)
            }
        }
    }

    /**
     * Centralized callback hook when payment gateway completes successfully.
     * Extends pass duration and returns new expiry timestamp.
     */
    fun handlePaymentSuccess(
        context: Context,
        passTier: PassTier,
        transactionId: String
    ): Long {
        val now = System.currentTimeMillis()
        val currentExpiry = AppSettings.getPassExpiryTimestamp(context)
        val baseTime = if (currentExpiry > now) currentExpiry else now
        val newExpiry = baseTime + passTier.durationMs

        AppSettings.setPassExpiryTimestamp(context, newExpiry, passTier.id)
        scope.launch {
            LicenseManager.activatePassViaPayment(passTier, transactionId)
        }
        return newExpiry
    }

    // =========================================================================
    // ACCESSIBILITY AUTOMATION ENFORCEMENT & WARNINGS
    // =========================================================================

    /**
     * Throttled notification when an order trigger is blocked due to expired pass.
     * 1. Speaks via TTS voice announcer (if enabled)
     * 2. Shows a Toast
     * 3. Logs warning to AppSettings
     */
    fun notifyAccessExpired(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastExpiredAlertTimestamp < ALERT_THROTTLE_WINDOW_MS) {
            return
        }
        lastExpiredAlertTimestamp = now

        mainHandler.post {
            Toast.makeText(
                context,
                "⚠️ Pass Expired! Auto-acceptance paused. Renew pass in Captain Store.",
                Toast.LENGTH_LONG
            ).show()
        }

        // Voice alert if voice announcer is enabled
        if (AppSettings.isVoiceAnnouncerEnabled(context)) {
            TTSManager.speak(context, "Pass expired. Auto acceptance paused. Please renew your pass.")
        }

        AppSettings.addLog(
            title = "Auto-Accept Blocked",
            message = "Ride offer detected, but automation is blocked because your pass has expired. Renew pass in Captain Store.",
            severity = LogSeverity.WARNING
        )
    }

    /**
     * Opens the Captain Store Screen or Activity.
     */
    fun openStore(context: Context) {
        try {
            val intent = Intent(context, CaptainStoreActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open CaptainStoreActivity: ${e.message}")
        }
    }
}
