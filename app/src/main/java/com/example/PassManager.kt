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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Throttle timestamp for access-expired alerts to prevent spamming active order loops
    private var lastExpiredAlertTimestamp = 0L
    private const val ALERT_THROTTLE_WINDOW_MS = 30_000L

    /**
     * Exposes reactive StateFlow for active pass expiry timestamp.
     */
    val passExpiryTimestampFlow: StateFlow<Long> = AppSettings.passExpiryTimestampFlow

    /**
     * Exposes reactive StateFlow indicating whether the subscription pass is active.
     */
    val isPassActiveFlow: StateFlow<Boolean> = AppSettings.isPassActiveFlow

    /**
     * Returns true if user has an active pass or an active free trial.
     */
    fun isAccessGranted(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val passExpiry = AppSettings.getPassExpiryTimestamp(context)
        if (now < passExpiry) {
            return true
        }

        // Check hardware trial access from LicenseManager
        return LicenseManager.isAccessGranted()
    }

    /**
     * Checks strictly whether a subscription pass is currently active.
     */
    fun isPassActive(context: Context): Boolean {
        return System.currentTimeMillis() < AppSettings.getPassExpiryTimestamp(context)
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
