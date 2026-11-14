package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.AppSettings
import com.example.DeviceUtils
import com.example.LicenseManager
import com.example.PassManager
import com.example.PassTier
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Data model for documents in /received_payments/{utr_number}
 * Populated automatically by the automated server from bank SMS/notifications.
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
 * 1. Scenario A (Server updated first):
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
 *      * As soon as the server writes the verified document, trigger verification, mark isUsed = true,
 *        update user status, and automatically unlock the app.
 */
class PaymentVerificationRepository(
    customFirestore: FirebaseFirestore? = null
) {
    private val firestore: FirebaseFirestore by lazy {
        customFirestore ?: FirebaseFirestore.getInstance()
    }

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
                amount == 199L -> PassTier.REMOVE_ADS
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
     * Checks Firestore user profile `/users/{userId}` for active pass status:
     * if (currentTime < passExpiryDate && paymentStatus == "SUCCESS") -> Pass Active.
     * else -> Pass Expired / Inactive.
     */
    suspend fun checkIsPaymentVerified(context: Context): Boolean {
        val userId = resolveUserId(context)
        if (userId.isBlank() || !isNetworkAvailable(context)) {
            Log.d(TAG, "Device is offline or unauthenticated. Keeping cached pass status.")
            return PassManager.isPassActive(context)
        }

        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Log.w(TAG, "FirebaseApp is not initialized; skipping checkIsPaymentVerified.")
                return PassManager.isPassActive(context)
            }
            val doc = firestore.collection(COLL_USERS).document(userId).get().await()
            if (doc.exists()) {
                val passTypeStr = doc.getString("activePassType") ?: doc.getString("passType")
                val parsedTier = PassTier.fromId(passTypeStr)
                val isProTier = parsedTier?.isProPass == true
                val paymentStatus = doc.getString("payment_status")
                    ?: doc.getString("paymentStatus")
                    ?: if (doc.getBoolean("isPaymentVerified") == true || (!passTypeStr.isNullOrBlank() && isProTier)) "SUCCESS" else "FAILED"

                val passExpiryDate = doc.getLong("passExpiryTimestamp")
                    ?: doc.getLong("pass_expiry_date")
                    ?: doc.getLong("passExpiryDate")
                    ?: doc.getLong("passExpiry")
                    ?: doc.getLong("subscriptionExpiryTimestamp")
                    ?: doc.getDate("subscriptionExpiry")?.time
                    ?: 0L

                val currentTime = LicenseManager.getCurrentServerTimeMs()
                val isPaymentSuccessful = paymentStatus.equals("SUCCESS", ignoreCase = true) ||
                    paymentStatus.equals("VERIFIED", ignoreCase = true)
                val isActive = isPaymentSuccessful && passExpiryDate > currentTime

                PassManager.setPassStatus(context, isActive)
                Log.d(TAG, "checkIsPaymentVerified for user $userId: isActive=$isActive (status=$paymentStatus, expiry=$passExpiryDate)")
                isActive
            } else {
                Log.d(TAG, "checkIsPaymentVerified for user $userId: user doc does not exist")
                PassManager.setPassStatus(context, false)
                false
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                e.message?.contains("offline", ignoreCase = true) == true
            ) {
                Log.w(TAG, "Firestore unavailable or client offline: ${e.message}. Using cached pass status.")
            } else {
                Log.w(TAG, "Firestore exception in checkIsPaymentVerified: ${e.message}")
            }
            PassManager.isPassActive(context)
        } catch (e: Exception) {
            Log.w(TAG, "checkIsPaymentVerified query failed: ${e.message}")
            PassManager.isPassActive(context)
        }
    }

    private val _verificationState = MutableStateFlow<PaymentVerificationState>(PaymentVerificationState.Idle)
    val verificationState: StateFlow<PaymentVerificationState> = _verificationState.asStateFlow()

    private var realtimeListener: ListenerRegistration? = null
    private var activeListeningUtr: String? = null
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Resolves the primary unique identifier for the user:
     * Strictly delegates to LicenseManager.resolveUserKey to ensure a single, consistent Document ID:
     * - Firebase Auth UID when logged in
     * - Hardware ID fallback only when logged out
     */
    fun resolveUserId(context: Context): String {
        return LicenseManager.resolveUserKey(context)
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

        val currentUid = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
        val userId = resolveUserId(context)
        if (currentUid.isNullOrBlank() || userId.isBlank()) {
            _verificationState.value = PaymentVerificationState.Error(
                message = "Please sign in with Google first before verifying payment.",
                canRetry = false,
                utr = utr
            )
            return
        }
        _verificationState.value = PaymentVerificationState.Loading("Checking transaction reference with payment registry...")

        repoScope.launch {
            try {
                val paymentDocRef = firestore.collection(COLL_RECEIVED_PAYMENTS).document(utr)
                val pendingDocRef = firestore.collection(COLL_PENDING_VERIFICATIONS).document(utr)

                val snapshot = try {
                    paymentDocRef.get().await()
                } catch (e: Exception) {
                    Log.d(TAG, "Direct get on received_payments suppressed under Zero-Trust rules: ${e.message}")
                    null
                }
                val pendingSnapshot = try {
                    pendingDocRef.get().await()
                } catch (_: Exception) {
                    null
                }

                val existingSnap = when {
                    snapshot != null && snapshot.exists() -> snapshot
                    pendingSnapshot != null && pendingSnapshot.exists() -> pendingSnapshot
                    else -> null
                }

                if (existingSnap != null) {
                    val isUsed = existingSnap.getBoolean("isUsed") ?: (existingSnap.getBoolean("claimed") ?: false)
                    val status = existingSnap.getString("status") ?: ""
                    val amount = existingSnap.getLong("amount") ?: (selectedTier?.priceInInr?.toLong() ?: 9L)

                    if (isUsed) {
                        _verificationState.value = PaymentVerificationState.Error(
                            message = "This UTR ($utr) has already been redeemed and cannot be used again.",
                            canRetry = false,
                            utr = utr
                        )
                        return@launch
                    }

                    if (status.equals("Verified", ignoreCase = true)) {
                        executeClaimAndUnlock(
                            context = context,
                            utr = utr,
                            amount = amount,
                            userId = userId,
                            isImmediate = true,
                            preferredTier = selectedTier
                        )
                        return@launch
                    }
                }

                // Register pending verification only if it does not already exist (Zero-Trust create rule)
                if (pendingSnapshot == null || !pendingSnapshot.exists()) {
                    val tier = selectedTier ?: mapAmountToPassTier(9L)
                    val pendingPayload = hashMapOf<String, Any>(
                        "status" to "PENDING",
                        "passName" to tier.title,
                        "passTier" to tier.id,
                        "amount" to tier.priceInInr,
                        "isUsed" to false,
                        "utr" to utr,
                        "userId" to userId,
                        "firebaseUid" to currentUid,
                        "timestamp" to FieldValue.serverTimestamp(),
                        "deviceHardwareId" to com.example.HardwareIdManager.getPermanentHardwareId(context)
                    )
                    pendingDocRef.set(pendingPayload).await()
                }

                _verificationState.value = PaymentVerificationState.Pending(
                    utr = utr,
                    message = "UTR submitted! Waiting for secure server bank confirmation..."
                )

                attachRealtimePaymentListener(
                    context = context,
                    utr = utr,
                    userId = userId,
                    preferredTier = selectedTier
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error querying payment registry", e)
                _verificationState.value = PaymentVerificationState.Error(
                    message = "Error submitting UTR: ${e.localizedMessage ?: "Unknown error"}. Please check your connection and retry.",
                    canRetry = true,
                    utr = utr
                )
            }
        }
    }

    /**
     * Attaches a real-time snapshot listener on pending_verifications/{utr} for Scenario B.
     */
    private fun attachRealtimePaymentListener(
        context: Context,
        utr: String,
        userId: String,
        preferredTier: PassTier?
    ) {
        cancelPendingListener()
        activeListeningUtr = utr

        val pendingDocRef = firestore.collection(COLL_PENDING_VERIFICATIONS).document(utr)
        realtimeListener = pendingDocRef.addSnapshotListener { snapshot, error ->
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
     * Enqueues Zero-Trust `ACTIVATE_PASS_PAYMENT` action and awaits server confirmation.
     * Client NEVER writes directly to `received_payments` or `users`.
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
            val actionResult = LicenseManager.activatePassViaPayment(passTier, utr)
            if (actionResult.isFailure) {
                val errMsg = actionResult.exceptionOrNull()?.message ?: "Payment could not be verified by server."
                _verificationState.value = PaymentVerificationState.Error(
                    message = errMsg,
                    canRetry = true,
                    utr = utr
                )
                return
            }

            val now = LicenseManager.getCurrentServerTimeMs()
            val newExpiryTimestamp = AppSettings.getPassExpiryTimestamp(context)
                .takeIf { it > now } ?: (now + passTier.durationMs)
            val newExpiryDate = Date(newExpiryTimestamp)

            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                PassManager.setPassStatus(context, true)
                AppSettings.addLog(
                    title = "Payment Verified & Unlocked",
                    message = "UTR: $utr (₹$amount). Activated ${passTier.title} until $newExpiryDate.",
                    severity = com.example.LogSeverity.INFO
                )
            }

            cancelPendingListener()

            _verificationState.value = PaymentVerificationState.Success(
                utr = utr,
                amount = amount,
                passTier = passTier,
                expiryTimestamp = newExpiryTimestamp,
                isImmediate = isImmediate,
                message = "Payment of ₹$amount verified successfully! ${passTier.title} is now active."
            )

            Log.i(TAG, "Successfully claimed UTR $utr via Action Queue for user $userId. Unlocked until $newExpiryTimestamp.")

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
