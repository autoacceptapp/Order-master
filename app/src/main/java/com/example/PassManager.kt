package com.example

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.example.data.PaymentVerificationRepository
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

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

    private val _isPassActiveFlow = MutableStateFlow(false)

    init {
        scope.launch {
            LicenseManager.accessStatus.collect { status ->
                val active = when (status) {
                    is AccessStatus.PassActive -> status.remainingTimeMs > 0
                    is AccessStatus.TrialActive -> status.remainingTimeMs > 0
                    else -> false
                }
                _isPassActiveFlow.value = active
            }
        }
    }

    /**
     * Exposes reactive StateFlow indicating whether the subscription pass is active.
     */
    val isPassActiveFlow: StateFlow<Boolean> = _isPassActiveFlow.asStateFlow()

    /**
     * Checks strictly whether a subscription pass is currently active.
     * Evaluates strictly from in-memory StateFlow.
     * Zero local storage (no SharedPreferences).
     */
    fun isPassActive(context: Context? = null): Boolean {
        return _isPassActiveFlow.value
    }

    /**
     * Updates pass status strictly in memory.
     * ZERO local storage (no SharedPreferences).
     */
    fun setPassStatus(context: Context? = null, status: Boolean) {
        _isPassActiveFlow.value = status
    }

    /**
     * Returns true allowing access and permissions without blocking when pass is not purchased.
     */
    fun isAccessGranted(context: Context): Boolean {
        return true
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
     * 1. Payment Response Verification (Frontend & Backend):
     * Pass ka status (isPassActive = true) sirf aur sirf tabhi update karein
     * jab Firestore backend se verified Success signal aaye.
     */
    fun onPaymentSuccess(
        context: Context,
        paymentId: String,
        preferredTier: PassTier? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        verifyPaymentWithBackend(context, paymentId, preferredTier) { isSuccess ->
            if (isSuccess) {
                setPassStatus(context, true)
                Toast.makeText(context, "Pass Activated!", Toast.LENGTH_SHORT).show()
                onComplete?.invoke(true)
            } else {
                setPassStatus(context, false)
                Toast.makeText(context, "Payment not verified yet. Please wait a minute or check your UTR", Toast.LENGTH_SHORT).show()
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
        preferredTier: PassTier? = null,
        onResult: (Boolean) -> Unit
    ) {
        scope.launch {
            val isSuccess = verifyPaymentWithBackendSuspend(context, paymentId, preferredTier)
            withContext(Dispatchers.Main) {
                onResult(isSuccess)
            }
        }
    }

    fun verifyPaymentWithBackend(
        context: Context,
        paymentId: String,
        onResult: (Boolean) -> Unit
    ) {
        verifyPaymentWithBackend(context, paymentId, null, onResult)
    }

    private class AlreadyClaimedException(message: String) : Exception(message)

    private sealed interface RealtimeWaitOutcome {
        data object VerifiedReadyToClaim : RealtimeWaitOutcome
        data class EarlyResult(val result: VerificationResult) : RealtimeWaitOutcome
    }

    /**
     * Executes the ACID Firestore transaction to claim an already verified UTR payment,
     * update the user's pass expiry, and unlock pass access locally.
     */
    private suspend fun executeClaimTransaction(
        firestore: FirebaseFirestore,
        paymentDocRef: DocumentReference,
        userDocRef: DocumentReference,
        userId: String,
        cleanUtr: String,
        preferredTier: PassTier?,
        context: Context
    ): VerificationResult {
        var finalExpiryTimestamp = 0L
        var activatedTier: PassTier? = null

        firestore.runTransaction { transaction ->
            val transPaymentDoc = transaction.get(paymentDocRef)
            val transUserDoc = transaction.get(userDocRef)

            if (!transPaymentDoc.exists()) {
                throw IllegalStateException("Payment record does not exist.")
            }

            val transStatus = transPaymentDoc.getString("status") ?: ""
            if (!transStatus.equals("Verified", ignoreCase = true)) {
                throw IllegalStateException("Payment is not verified (status: $transStatus).")
            }

            val transUsed = transPaymentDoc.getBoolean("isUsed") ?: false
            val transClaimedBy = transPaymentDoc.getString("claimedBy")
            if (transUsed && transClaimedBy != userId) {
                throw AlreadyClaimedException("This UTR has already been claimed on another account.")
            }

            val amount = transPaymentDoc.getLong("amount") ?: 0L
            val passTier = preferredTier ?: PaymentVerificationRepository.mapAmountToPassTier(amount)
            activatedTier = passTier

            val currentTime = System.currentTimeMillis()
            val existingExpiry = transUserDoc.getLong("pass_expiry_date")
                ?: transUserDoc.getLong("passExpiryDate")
                ?: 0L
            val baseTime = if (existingExpiry > currentTime) existingExpiry else currentTime
            val newExpiryTimestamp = baseTime + passTier.durationMs
            finalExpiryTimestamp = newExpiryTimestamp

            // 1. Update received_payments/{UTR} -> set isUsed = true and claimedBy = {current_userId}
            transaction.update(
                paymentDocRef,
                mapOf(
                    "isUsed" to true,
                    "claimedBy" to userId,
                    "claimedAt" to FieldValue.serverTimestamp(),
                    "planId" to passTier.id
                )
            )

            // 2. Update users/{current_userId}
            val userData = mutableMapOf<String, Any>(
                "payment_status" to "SUCCESS",
                "paymentStatus" to "SUCCESS",
                "isPaymentVerified" to true,
                "lastVerifiedUtr" to cleanUtr,
                "pass_expiry_date" to newExpiryTimestamp,
                "passExpiryDate" to newExpiryTimestamp,
                "subscriptionExpiryTimestamp" to newExpiryTimestamp,
                "subscriptionExpiry" to java.util.Date(newExpiryTimestamp),
                "activePassTier" to passTier.id,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            if (transUserDoc.exists()) {
                transaction.update(userDocRef, userData)
            } else {
                userData["userId"] = userId
                userData["createdAt"] = FieldValue.serverTimestamp()
                transaction.set(userDocRef, userData)
            }
        }.await()

        val tier = activatedTier ?: (preferredTier ?: PassTier.DAILY)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
            setPassStatus(context, true)
            LicenseManager.activatePassViaPayment(tier, cleanUtr)
            AppSettings.addLog(
                title = "Pass Activated",
                message = "${tier.title} activated via UTR $cleanUtr. Valid for ${tier.durationDays} day(s).",
                severity = LogSeverity.MATCH_ACCEPTED
            )
        }

        return VerificationResult.Success(tier, finalExpiryTimestamp)
    }

    /**
     * Detailed verification of the user's inputted UTR string with Firestore.
     * Implements a Real-time Waiting & Claiming architecture:
     * - SCENARIO A (MacroDroid came first): Claims immediately if already "Verified" and unused.
     * - SCENARIO B (App came first): Sets status to "Pending", attaches a real-time snapshot listener,
     *   waits up to 90 seconds for MacroDroid to mark "Verified", and claims instantly when confirmed.
     */
    suspend fun verifyPaymentWithBackendDetailed(
        context: Context,
        utr: String,
        preferredTier: PassTier? = null
    ): VerificationResult = withContext(Dispatchers.IO) {
        val cleanUtr = utr.trim().filter { it.isDigit() }
        if (cleanUtr.length != 12) {
            return@withContext VerificationResult.Invalid("Please enter a valid 12-digit UTR/UPI reference number.")
        }

        try {
            val firestore = FirebaseFirestore.getInstance()
            val userId = PaymentVerificationRepository.defaultInstance.resolveUserId(context)
            val paymentDocRef = firestore.collection(PaymentVerificationRepository.COLL_RECEIVED_PAYMENTS).document(cleanUtr)
            val userDocRef = firestore.collection("users").document(userId)

            // Step 1: Check existing document in received_payments/{cleanUtr}
            val snapshot = paymentDocRef.get().await()

            if (snapshot.exists()) {
                val status = snapshot.getString("status") ?: ""
                val isUsed = snapshot.getBoolean("isUsed") ?: false
                val claimedBy = snapshot.getString("claimedBy")

                if (isUsed && claimedBy != userId) {
                    Log.w(TAG, "Payment $cleanUtr already claimed by another user: '$claimedBy'.")
                    return@withContext VerificationResult.AlreadyUsed(
                        "This UTR has already been claimed on another account."
                    )
                }

                // SCENARIO A: MacroDroid came first (status == "Verified" && !isUsed)
                if (status.equals("Verified", ignoreCase = true) && !isUsed) {
                    Log.i(TAG, "Scenario A: MacroDroid verified $cleanUtr first. Claiming immediately...")
                    return@withContext executeClaimTransaction(
                        firestore = firestore,
                        paymentDocRef = paymentDocRef,
                        userDocRef = userDocRef,
                        userId = userId,
                        cleanUtr = cleanUtr,
                        preferredTier = preferredTier,
                        context = context
                    )
                }
            }

            // SCENARIO B: App came first OR status == "Pending" / not yet "Verified"
            Log.i(TAG, "Scenario B: Real-time listener waiting for MacroDroid confirmation for $cleanUtr...")

            // 1. Create/Set the document with {"utr": cleanUtr, "status": "Pending", "isUsed": false}
            paymentDocRef.set(
                mapOf(
                    "utr" to cleanUtr,
                    "status" to "Pending",
                    "isUsed" to false,
                    "createdAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            // 2. Suspend with real-time snapshot listener wrapped in 90-second timeout
            val waitOutcome = withTimeoutOrNull(90_000L) {
                suspendCancellableCoroutine<RealtimeWaitOutcome> { continuation ->
                    var listener: ListenerRegistration? = null
                    val isResumed = AtomicBoolean(false)

                    listener = paymentDocRef.addSnapshotListener { docSnap, error ->
                        if (error != null) {
                            Log.w(TAG, "Real-time snapshot listener error for $cleanUtr: ${error.message}")
                            return@addSnapshotListener
                        }

                        if (docSnap != null && docSnap.exists()) {
                            val currentStatus = docSnap.getString("status") ?: ""
                            val currentUsed = docSnap.getBoolean("isUsed") ?: false
                            val currentClaimedBy = docSnap.getString("claimedBy")

                            // Check if claimed by another user
                            if (currentUsed && currentClaimedBy != userId) {
                                if (isResumed.compareAndSet(false, true)) {
                                    listener?.remove()
                                    continuation.resume(
                                        RealtimeWaitOutcome.EarlyResult(
                                            VerificationResult.AlreadyUsed("This UTR has already been claimed on another account.")
                                        )
                                    )
                                }
                                return@addSnapshotListener
                            }

                            // The moment MacroDroid updates status to "Verified" (and isUsed is still false)
                            if (currentStatus.equals("Verified", ignoreCase = true) && !currentUsed) {
                                if (isResumed.compareAndSet(false, true)) {
                                    // DETACH the listener immediately
                                    listener?.remove()
                                    continuation.resume(RealtimeWaitOutcome.VerifiedReadyToClaim)
                                }
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        Log.d(TAG, "Detaching real-time snapshot listener on cancellation/timeout for $cleanUtr")
                        listener?.remove()
                    }
                }
            }

            when (waitOutcome) {
                is RealtimeWaitOutcome.VerifiedReadyToClaim -> {
                    Log.i(TAG, "Real-time listener received 'Verified'. Executing claim transaction for $cleanUtr...")
                    executeClaimTransaction(
                        firestore = firestore,
                        paymentDocRef = paymentDocRef,
                        userDocRef = userDocRef,
                        userId = userId,
                        cleanUtr = cleanUtr,
                        preferredTier = preferredTier,
                        context = context
                    )
                }
                is RealtimeWaitOutcome.EarlyResult -> {
                    waitOutcome.result
                }
                null -> {
                    // 90-second timeout expired
                    Log.w(TAG, "Real-time verification timed out after 90 seconds for $cleanUtr")
                    VerificationResult.NotFound("Verification timeout. Please check your UTR or try again later.")
                }
            }

        } catch (e: AlreadyClaimedException) {
            Log.w(TAG, "Already claimed notice: ${e.message}")
            VerificationResult.AlreadyUsed(e.message ?: "This UTR has already been claimed.")
        } catch (e: Exception) {
            Log.e(TAG, "verifyPaymentWithBackendDetailed failed for $cleanUtr: ${e.message}", e)
            val msg = e.localizedMessage ?: "Network or verification error. Please retry."
            VerificationResult.Error("Error verifying payment: $msg")
        }
    }

    /**
     * Asynchronous suspend verification of UTR with Firestore backend.
     * Returns true if document exists, status == "Verified", isUsed == false,
     * and transaction completes successfully.
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
     * Checks if active network connectivity is available.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
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
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Device is offline. Keeping cached local pass status.")
            return@withContext isPassActive(context)
        }

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
                        Log.i(TAG, "Server check: Pass is ACTIVE until $passExpiryDate for user $userId")
                    } else {
                        setPassStatus(context, false)
                        Log.w(TAG, "Server check: Pass is EXPIRED/INACTIVE (expiry=$passExpiryDate, status=$paymentStatus) for user $userId")
                    }
                }
                isActive
            } else {
                withContext(Dispatchers.Main) {
                    setPassStatus(context, false)
                }
                false
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                e.message?.contains("offline", ignoreCase = true) == true
            ) {
                Log.w(TAG, "Firestore unavailable or client offline: ${e.message}.")
            } else {
                Log.w(TAG, "Firestore exception in verifyServerPassStatus: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                isPassActive(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "verifyServerPassStatus error: ${e.message}")
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
     * Returns active pass expiry timestamp strictly from LicenseManager in-memory server state.
     * Zero local storage.
     */
    fun getPassExpiryTimestamp(context: Context? = null): Long {
        return when (val status = LicenseManager.accessStatus.value) {
            is AccessStatus.PassActive -> status.expiryTimestamp
            is AccessStatus.TrialActive -> status.expiryTimestamp
            else -> 0L
        }
    }

    /**
     * Formats remaining time for the current pass into a human-readable string.
     */
    fun getFormattedTimeRemaining(context: Context? = null): String {
        return when (val status = LicenseManager.accessStatus.value) {
            is AccessStatus.PassActive -> status.formattedRemaining
            is AccessStatus.TrialActive -> status.formattedRemaining
            else -> "Expired"
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
            val newExpiry = getPassExpiryTimestamp(context)
            AppSettings.addLog(
                title = "Pass Activated",
                message = "${passTier.title} activated with ${passTier.pointsCost} Points. Valid for ${passTier.durationDays} day(s).",
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
        payeeVpa: String = "autoaccept6122-1@okhdfcbank",
        payeeName: String = "OrderMaster Captain Store",
        transactionRef: String = "ORDER_${passTier.id}_${System.currentTimeMillis()}"
    ): Uri {
        val formattedAmount = java.lang.String.format(java.util.Locale.US, "%.2f", passTier.priceInInr.toDouble())
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
    fun createUpiPaymentIntent(passTier: PassTier): Intent {
        val uri = buildUpiUri(passTier)
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
     *
     * Features multi-tier fallbacks:
     * 1. System Chooser intent
     * 2. Direct ACTION_VIEW intent
     * 3. Explicit installed UPI package targeting (GPay/PhonePe/Paytm/BHIM/Cred)
     * 4. Clipboard copy fallback with guidance if intent cannot resolve
     */
    fun initiateUpiPayment(
        activity: Activity,
        passTier: PassTier,
        onSuccess: (txnId: String) -> Unit = {},
        onFailed: (reason: String) -> Unit = {}
    ) {
        val pm = activity.packageManager
        val baseIntent = createUpiPaymentIntent(passTier)

        // Find which known UPI apps are installed
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
            val chooser = Intent.createChooser(baseIntent, "Pay ₹${passTier.priceInInr} for ${passTier.title}")
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
                val directIntent = createUpiPaymentIntent(passTier).apply {
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
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                val clip = android.content.ClipData.newPlainText("UPI ID", "autoaccept6122-1@okhdfcbank")
                clipboard.setPrimaryClip(clip)
            }
            android.widget.Toast.makeText(
                activity,
                "UPI ID copied: autoaccept6122-1@okhdfcbank. Please open your UPI app to pay ₹${passTier.priceInInr}.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            onFailed("UPI ID copied: autoaccept6122-1@okhdfcbank. Please pay in your UPI app and enter UTR.")
        } catch (e: Exception) {
            Log.w(TAG, "UPI intent launch and fallback failed: ${e.message}")
            android.widget.Toast.makeText(
                activity,
                "Please pay to UPI ID: autoaccept6122-1@okhdfcbank",
                android.widget.Toast.LENGTH_LONG
            ).show()
            onFailed("Please pay to UPI ID: autoaccept6122-1@okhdfcbank")
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
