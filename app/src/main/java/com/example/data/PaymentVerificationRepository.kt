package com.example.data

import android.content.Context
import android.util.Log
import com.example.AppSettings
import com.example.DeviceUtils
import com.example.LicenseManager
import com.example.PassManager
import com.example.PassTier
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Data model for documents in /received_payments/{utr_number}
 * Populated automatically by MacroDroid on the merchant device from bank SMS/notifications.
 */
data class ReceivedPayment(
    val utr: String = "",
    val amount: Long = 0L,
    val status: String = "",
    val isUsed: Boolean = false
)

/**
 * Distinct UI and business states for the payment verification pipeline.
 */
sealed class PaymentVerificationState {
    data object Idle : PaymentVerificationState()

    data class Loading(val message: String) : PaymentVerificationState()

    data class Pending(
        val utr: String,
        val message: String,
        val submittedAtMs: Long = System.currentTimeMillis()
    ) : PaymentVerificationState()

    data class Success(
        val utr: String,
        val amount: Long,
        val passTier: PassTier,
        val expiryTimestamp: Long,
        val isImmediate: Boolean, // true = Scenario A (found immediately), false = Scenario B (via real-time listener)
        val message: String
    ) : PaymentVerificationState()

    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val utr: String? = null
    ) : PaymentVerificationState()
}

/**
 * PaymentVerificationRepository
 *
 * Implements the automated verification architecture:
 *
 * 1. Scenario A (MacroDroid updated first):
 *    - Query `/received_payments/{utr}`.
 *    - If document exists, status == "Verified", and isUsed == false:
 *      * Mark isUsed = true.
 *      * Update user profile `/users/{userId}` setting isPaymentVerified = true & subscriptionExpiry.
 *      * Unlock app immediately (AppSettings & LicenseManager).
 *
 * 2. Scenario B (User enters UTR first):
 *    - If document does not exist in `/received_payments/{utr}`:
 *      * Save pending entry in `/pending_verifications/{utr}` with { userId, status: "PENDING", timestamp }.
 *      * Attach real-time snapshot listener on `/received_payments/{utr}`.
 *      * As soon as MacroDroid writes the verified document, trigger verification, mark isUsed = true,
 *        update user status, and automatically unlock the app.
 */
class PaymentVerificationRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    companion object {
        private const val TAG = "PaymentVerificationRepo"
        const val COLL_RECEIVED_PAYMENTS = "received_payments"
        const val COLL_PENDING_VERIFICATIONS = "pending_verifications"
        const val COLL_USERS = "users"

        val UTR_REGEX = Regex("^[0-9]{12}$")

        fun isValidUtr(utr: String): Boolean = utr.matches(UTR_REGEX)

        /**
         * Maps received INR payment amount to the corresponding Pass Tier.
         * Default tiers: Daily (₹9), Weekly (₹49), Monthly (₹179).
         */
        fun mapAmountToPassTier(amount: Long): PassTier {
            return when {
                amount >= 179 -> PassTier.MONTHLY
                amount >= 49 -> PassTier.WEEKLY
                else -> PassTier.DAILY
            }
        }

        val defaultInstance by lazy { PaymentVerificationRepository() }

        /**
         * Convenience static method to inspect Firestore /users/{userId} for isPaymentVerified.
         */
        suspend fun checkUserPaymentStatus(context: Context): Boolean {
            return defaultInstance.checkIsPaymentVerified(context)
        }
    }

    /**
     * Checks Firestore user profile `/users/{userId}` for active pass status:
     * if (currentTime < passExpiryDate && paymentStatus == "SUCCESS") -> Pass Active.
     * else -> Pass Expired / Inactive.
     */
    suspend fun checkIsPaymentVerified(context: Context): Boolean {
        val userId = resolveUserId(context)
        return try {
            val doc = firestore.collection(COLL_USERS).document(userId).get().await()
            if (doc.exists()) {
                val isVerified = doc.getBoolean("isPaymentVerified") ?: false
                val paymentStatus = doc.getString("payment_status")
                    ?: doc.getString("paymentStatus")
                    ?: if (isVerified) "SUCCESS" else "FAILED"

                val passExpiryDate = doc.getLong("pass_expiry_date")
                    ?: doc.getLong("passExpiryDate")
                    ?: doc.getLong("subscriptionExpiryTimestamp")
                    ?: doc.getDate("subscriptionExpiry")?.time
                    ?: 0L

                val currentTime = System.currentTimeMillis()
                val isPaymentSuccessful = paymentStatus.equals("SUCCESS", ignoreCase = true) || isVerified
                val isActive = isPaymentSuccessful && (passExpiryDate == 0L || currentTime < passExpiryDate)

                PassManager.setPassStatus(context, isActive)
                if (isActive && passExpiryDate > 0L) {
                    AppSettings.setPassExpiryTimestamp(context, passExpiryDate)
                } else if (!isActive) {
                    AppSettings.setPassExpiryTimestamp(context, 0L)
                }

                Log.d(TAG, "checkIsPaymentVerified for user $userId: isActive=$isActive (status=$paymentStatus, expiry=$passExpiryDate)")
                isActive
            } else {
                Log.d(TAG, "checkIsPaymentVerified for user $userId: user doc does not exist")
                PassManager.setPassStatus(context, false)
                AppSettings.setPassExpiryTimestamp(context, 0L)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkIsPaymentVerified query failed: ${e.message}", e)
            PassManager.isPassActive(context)
        }
    }

    private val _verificationState = MutableStateFlow<PaymentVerificationState>(PaymentVerificationState.Idle)
    val verificationState: StateFlow<PaymentVerificationState> = _verificationState.asStateFlow()

    private var realtimeListener: ListenerRegistration? = null
    private var activeListeningUtr: String? = null
    private val repoScope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * Resolves the primary unique identifier for the user:
     * 1. Firebase Auth UID
     * 2. Sanitized email address
     * 3. Hardware device ID
     */
    fun resolveUserId(context: Context): String {
        val authUser = FirebaseAuth.getInstance().currentUser
        if (!authUser?.uid.isNullOrBlank()) {
            return authUser!!.uid
        }
        val email = authUser?.email
        if (!email.isNullOrBlank()) {
            return "email_" + email.replace(".", "_").replace("@", "_at_")
        }
        return "hw_" + DeviceUtils.getDeviceHardwareId(context)
    }

    /**
     * Initiates the payment verification flow for a given 12-digit UTR.
     */
    fun verifyPayment(
        context: Context,
        rawUtr: String,
        selectedTier: PassTier? = null
    ) {
        val utr = rawUtr.trim()

        // 1. Strict 12-Digit Numeric Validation
        if (!isValidUtr(utr)) {
            _verificationState.value = PaymentVerificationState.Error(
                message = "Invalid UTR number. The Reference/UTR must be exactly 12 numeric digits.",
                canRetry = true,
                utr = utr
            )
            return
        }

        // Cancel any previous real-time listener
        cancelPendingListener()

        val userId = resolveUserId(context)
        _verificationState.value = PaymentVerificationState.Loading("Checking transaction reference with payment registry...")

        repoScope.launch {
            try {
                val paymentDocRef = firestore.collection(COLL_RECEIVED_PAYMENTS).document(utr)
                val snapshot = paymentDocRef.get().await()

                if (snapshot.exists()) {
                    // =========================================================
                    // SCENARIO A: MacroDroid already pushed the payment!
                    // =========================================================
                    Log.i(TAG, "Scenario A: Document exists in $COLL_RECEIVED_PAYMENTS for UTR: $utr")
                    val isUsed = snapshot.getBoolean("isUsed") ?: false
                    val status = snapshot.getString("status") ?: ""
                    val amount = snapshot.getLong("amount") ?: (selectedTier?.priceInInr?.toLong() ?: 9L)

                    if (isUsed) {
                        _verificationState.value = PaymentVerificationState.Error(
                            message = "This UTR ($utr) has already been redeemed and cannot be used again.",
                            canRetry = false,
                            utr = utr
                        )
                        return@launch
                    }

                    if (!status.equals("Verified", ignoreCase = true)) {
                        _verificationState.value = PaymentVerificationState.Error(
                            message = "Payment record found with status '$status'. Please ensure your UPI payment is marked successful.",
                            canRetry = true,
                            utr = utr
                        )
                        return@launch
                    }

                    // Process claim atomically & unlock
                    executeClaimAndUnlock(
                        context = context,
                        utr = utr,
                        amount = amount,
                        userId = userId,
                        isImmediate = true,
                        preferredTier = selectedTier
                    )

                } else {
                    // =========================================================
                    // SCENARIO B: User entered UTR first!
                    // MacroDroid has not pushed the SMS yet.
                    // =========================================================
                    Log.i(TAG, "Scenario B: Document not found yet. Registering pending verification for UTR: $utr")

                    // 1. Save entry under /pending_verifications/{utr}
                    val pendingDocRef = firestore.collection(COLL_PENDING_VERIFICATIONS).document(utr)
                    val pendingPayload = hashMapOf<String, Any>(
                        "userId" to userId,
                        "utr" to utr,
                        "status" to "PENDING",
                        "timestamp" to FieldValue.serverTimestamp(),
                        "deviceHardwareId" to DeviceUtils.getDeviceHardwareId(context)
                    )
                    pendingDocRef.set(pendingPayload, SetOptions.merge()).await()

                    // 2. Transition state to Pending
                    _verificationState.value = PaymentVerificationState.Pending(
                        utr = utr,
                        message = "UTR submitted! Waiting for merchant phone (MacroDroid) bank SMS sync..."
                    )

                    // 3. Attach real-time snapshot listener on /received_payments/{utr}
                    attachRealtimePaymentListener(
                        context = context,
                        utr = utr,
                        userId = userId,
                        preferredTier = selectedTier
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying payment registry", e)
                _verificationState.value = PaymentVerificationState.Error(
                    message = "Network error verifying UTR: ${e.localizedMessage ?: "Unknown error"}. Please check your connection and retry.",
                    canRetry = true,
                    utr = utr
                )
            }
        }
    }

    /**
     * Attaches a real-time snapshot listener for Scenario B.
     */
    private fun attachRealtimePaymentListener(
        context: Context,
        utr: String,
        userId: String,
        preferredTier: PassTier?
    ) {
        cancelPendingListener()
        activeListeningUtr = utr

        val paymentDocRef = firestore.collection(COLL_RECEIVED_PAYMENTS).document(utr)
        realtimeListener = paymentDocRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Realtime listener error for UTR $utr: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val isUsed = snapshot.getBoolean("isUsed") ?: false
                val status = snapshot.getString("status") ?: ""
                val amount = snapshot.getLong("amount") ?: (preferredTier?.priceInInr?.toLong() ?: 9L)

                Log.i(TAG, "Realtime update received for UTR $utr: status=$status, isUsed=$isUsed, amount=$amount")

                if (status.equals("Verified", ignoreCase = true) && !isUsed) {
                    // Payment confirmed by MacroDroid! Claim and unlock
                    repoScope.launch {
                        executeClaimAndUnlock(
                            context = context,
                            utr = utr,
                            amount = amount,
                            userId = userId,
                            isImmediate = false,
                            preferredTier = preferredTier
                        )
                    }
                } else if (isUsed) {
                    _verificationState.value = PaymentVerificationState.Error(
                        message = "This transaction ($utr) was claimed by another request.",
                        canRetry = false,
                        utr = utr
                    )
                    cancelPendingListener()
                }
            }
        }
    }

    /**
     * Atomically executes the claim across Firestore collections and unlocks the application:
     * 1. Marks `/received_payments/{utr}` -> isUsed = true
     * 2. Updates `/users/{userId}` -> isPaymentVerified = true, subscriptionExpiry
     * 3. Updates `/pending_verifications/{utr}` -> status = "COMPLETED"
     * 4. Unlocks `AppSettings` and `LicenseManager` immediately.
     */
    private suspend fun executeClaimAndUnlock(
        context: Context,
        utr: String,
        amount: Long,
        userId: String,
        isImmediate: Boolean,
        preferredTier: PassTier?
    ) {
        try {
            val passTier = preferredTier ?: mapAmountToPassTier(amount)
            val now = System.currentTimeMillis()
            val currentExpiry = AppSettings.getPassExpiryTimestamp(context)
            val baseTime = if (currentExpiry > now) currentExpiry else now
            val newExpiryTimestamp = baseTime + passTier.durationMs
            val newExpiryDate = Date(newExpiryTimestamp)

            val paymentDocRef = firestore.collection(COLL_RECEIVED_PAYMENTS).document(utr)
            val userDocRef = firestore.collection(COLL_USERS).document(userId)
            val pendingDocRef = firestore.collection(COLL_PENDING_VERIFICATIONS).document(utr)

            // Step 1: Atomic batch / transaction to update all documents
            firestore.runBatch { batch ->
                // Mark received_payments
                batch.update(
                    paymentDocRef,
                    mapOf(
                        "isUsed" to true,
                        "claimedBy" to userId,
                        "claimedAt" to FieldValue.serverTimestamp()
                    )
                )

                // Update /users/{userId}
                batch.set(
                    userDocRef,
                    mapOf(
                        "isPaymentVerified" to true,
                        "payment_status" to "SUCCESS",
                        "paymentStatus" to "SUCCESS",
                        "pass_expiry_date" to newExpiryTimestamp,
                        "passExpiryDate" to newExpiryTimestamp,
                        "subscriptionExpiry" to newExpiryDate,
                        "subscriptionExpiryTimestamp" to newExpiryTimestamp,
                        "lastVerifiedUtr" to utr,
                        "verifiedAmount" to amount,
                        "activePassTier" to passTier.id,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )

                // Update pending_verifications
                batch.set(
                    pendingDocRef,
                    mapOf(
                        "status" to "COMPLETED",
                        "completedAt" to FieldValue.serverTimestamp(),
                        "amount" to amount
                    ),
                    SetOptions.merge()
                )
            }.await()

            // Step 2: Immediate Local and Memory Unlock (Double check passed)
            PassManager.setPassStatus(context, true)
            AppSettings.setPassExpiryTimestamp(context, newExpiryTimestamp, passTier.id)
            LicenseManager.activatePassViaPayment(passTier, utr)

            // Log activity
            AppSettings.addLog(
                title = "Payment Verified & Unlocked",
                message = "UTR: $utr (₹$amount). Activated ${passTier.title} until $newExpiryDate.",
                severity = com.example.LogSeverity.INFO
            )

            // Step 3: Remove listener since verification succeeded
            cancelPendingListener()

            // Step 4: Emit success state
            _verificationState.value = PaymentVerificationState.Success(
                utr = utr,
                amount = amount,
                passTier = passTier,
                expiryTimestamp = newExpiryTimestamp,
                isImmediate = isImmediate,
                message = "Payment of ₹$amount verified successfully! ${passTier.title} is now active."
            )

            Log.i(TAG, "Successfully claimed UTR $utr for user $userId. Unlocked until $newExpiryTimestamp.")

        } catch (e: Exception) {
            Log.e(TAG, "Error executing claim for UTR $utr", e)
            _verificationState.value = PaymentVerificationState.Error(
                message = "Failed to finalize verification: ${e.localizedMessage ?: "Unknown error"}. Please retry.",
                canRetry = true,
                utr = utr
            )
        }
    }

    /**
     * Detaches any active Firestore real-time listener to prevent memory leaks.
     */
    fun cancelPendingListener() {
        realtimeListener?.remove()
        realtimeListener = null
        activeListeningUtr = null
    }

    /**
     * Resets verification state back to Idle.
     */
    fun resetState() {
        cancelPendingListener()
        _verificationState.value = PaymentVerificationState.Idle
    }
}
