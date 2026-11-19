package com.example.data

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.AppSettings
import com.example.DeviceUtils
import com.example.HardwareIdManager
import com.example.LicenseManager
import com.example.LogSeverity
import com.example.PassManager
import com.example.PassPricingConfig
import com.example.PassTier
import com.example.data.repository.AppConfigRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

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
        val isImmediate: Boolean,
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
 * Canonical single source of truth for Zero-Trust UTR payment verification:
 * - Why both `pending_verifications/{utr}` and `pending_actions` (`ACTIVATE_PASS_PAYMENT`) exist:
 *   1. `pending_verifications/{utr}` is indexed by the 12-digit UTR document ID so the Admin App's
 *      SMS auto-matcher (`received_payments/{utr}` -> `pending_verifications/{utr}`) or manual Admin
 *      review can locate and mark the UTR as `APPROVED` (direct pass grant) or `VERIFIED` (bank SMS
 *      confirmed, ready for client claim).
 *   2. `pending_actions` (`ACTIVATE_PASS_PAYMENT`) is dispatched ONLY when a pre-verified bank SMS
 *      entry (`status == "VERIFIED"` and `isUsed == false`) needs `AdminSyncService` to atomically
 *      bind and activate the pass on `users/{uid}` without giving clients direct write access to `users`.
 * - Never reads `received_payments/{utr}` directly from client code (Admin-only in `firestore.rules`).
 */
class PaymentVerificationRepository(
    customFirestore: FirebaseFirestore? = null
) {
    private val firestore: FirebaseFirestore by lazy {
        customFirestore ?: FirebaseFirestore.getInstance()
    }

    @VisibleForTesting
    internal enum class UtrSnapshotDecision {
        PENDING,
        VERIFIED_READY_TO_CLAIM,
        APPROVED_ALREADY_APPLIED,
        ALREADY_CLAIMED_BY_OTHER,
        REJECTED
    }

    companion object {
        private const val TAG = "PaymentVerificationRepo"
        const val COLL_PENDING_VERIFICATIONS = "pending_verifications"
        const val COLL_USERS = "users"

        val UTR_REGEX = Regex("^[0-9]{12}$")

        fun isValidUtr(utr: String): Boolean = utr.matches(UTR_REGEX)

        /**
         * Maps received INR payment amount to the corresponding Pass Tier via centralized PassPricingConfig.
         */
        fun mapAmountToPassTier(amount: Long): PassTier? {
            return PassPricingConfig.mapAmountToPassTier(amount)
        }

        @VisibleForTesting
        internal data class VerificationRejectionDecision(
            val message: String,
            val isTerminal: Boolean,
            val canRetry: Boolean,
            val isAlreadyClaimed: Boolean = false,
            val isStillPending: Boolean = false
        )

        /**
         * Classifies payment/action rejection errors into clear user-facing messages and terminal
         * listener cancellation flags, including Step 4A fail-closed APK signature errors.
         */
        @VisibleForTesting
        internal fun classifyVerificationRejection(
            rawError: String?,
            errorCode: String? = null
        ): VerificationRejectionDecision {
            val combined = listOfNotNull(rawError?.trim(), errorCode?.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val isUntrustedSig = combined.contains("UNTRUSTED_APP_SIGNATURE", ignoreCase = true) ||
                combined.contains("App signature verification failed", ignoreCase = true)
            if (isUntrustedSig) {
                return VerificationRejectionDecision(
                    message = LicenseManager.formatActionQueueErrorMessage(
                        "ACTIVATE_PASS_PAYMENT",
                        rawError,
                        errorCode ?: "UNTRUSTED_APP_SIGNATURE"
                    ),
                    isTerminal = true,
                    canRetry = false
                )
            }
            val isMissingServerSigConfig = combined.contains("SECURITY_CONFIG_MISSING_APP_SHA256", ignoreCase = true) ||
                combined.contains("Server app signature verification is not configured", ignoreCase = true)
            if (isMissingServerSigConfig) {
                return VerificationRejectionDecision(
                    message = LicenseManager.formatActionQueueErrorMessage(
                        "ACTIVATE_PASS_PAYMENT",
                        rawError,
                        errorCode ?: "SECURITY_CONFIG_MISSING_APP_SHA256"
                    ),
                    isTerminal = true,
                    canRetry = true
                )
            }
            val isPaymentNotReceivedYet = combined.contains("PAYMENT_NOT_RECEIVED_YET", ignoreCase = true) ||
                combined.contains("Waiting for bank SMS confirmation", ignoreCase = true)
            if (isPaymentNotReceivedYet) {
                return VerificationRejectionDecision(
                    message = LicenseManager.formatActionQueueErrorMessage(
                        "ACTIVATE_PASS_PAYMENT",
                        rawError,
                        errorCode ?: "PAYMENT_NOT_RECEIVED_YET"
                    ),
                    isTerminal = false,
                    canRetry = true,
                    isStillPending = true
                )
            }
            val isAlreadyClaimed = combined.contains("already been claimed", ignoreCase = true) ||
                combined.contains("already claimed", ignoreCase = true) ||
                combined.contains("claimed by another", ignoreCase = true) ||
                combined.contains("ALREADY_USED", ignoreCase = true)
            if (isAlreadyClaimed) {
                return VerificationRejectionDecision(
                    message = "UTR already claimed by another user.",
                    isTerminal = true,
                    canRetry = false,
                    isAlreadyClaimed = true
                )
            }
            val isAmountMismatch = combined.contains("AMOUNT_MISMATCH", ignoreCase = true) ||
                combined.contains("amount does not match", ignoreCase = true)
            if (isAmountMismatch) {
                return VerificationRejectionDecision(
                    message = LicenseManager.formatActionQueueErrorMessage(
                        "ACTIVATE_PASS_PAYMENT",
                        rawError,
                        errorCode ?: "AMOUNT_MISMATCH"
                    ),
                    isTerminal = true,
                    canRetry = true
                )
            }
            val isStillPending = combined.contains("not confirmed", ignoreCase = true) ||
                combined.contains("not found", ignoreCase = true) ||
                combined.contains("not verified", ignoreCase = true) ||
                combined.contains("pending", ignoreCase = true)
            if (isStillPending) {
                return VerificationRejectionDecision(
                    message = "Payment still pending verification. Please wait a minute or check your UTR.",
                    isTerminal = false,
                    canRetry = true,
                    isStillPending = true
                )
            }
            val formatted = LicenseManager.formatActionQueueErrorMessage("ACTIVATE_PASS_PAYMENT", rawError, errorCode)
            val isTerminal = combined.contains("invalid", ignoreCase = true) ||
                combined.contains("rejected", ignoreCase = true) ||
                combined.contains("INVALID_PASS_TIER", ignoreCase = true)
            return VerificationRejectionDecision(
                message = formatted,
                isTerminal = isTerminal,
                canRetry = true
            )
        }

        @VisibleForTesting
        internal fun evaluateUtrSnapshot(
            status: String?,
            isUsed: Boolean,
            claimedBy: String?,
            docOwnerId: String?,
            currentUserId: String,
            currentFirebaseUid: String = currentUserId
        ): UtrSnapshotDecision {
            val cleanStatus = status?.trim().orEmpty()
            val cleanClaimedBy = claimedBy?.trim().orEmpty()
            val cleanOwner = docOwnerId?.trim().orEmpty()

            fun matchesCurrentUser(candidate: String): Boolean =
                candidate.isNotEmpty() && (candidate == currentUserId || (currentFirebaseUid.isNotBlank() && candidate == currentFirebaseUid))

            if (cleanClaimedBy.isNotEmpty() && !matchesCurrentUser(cleanClaimedBy)) {
                return UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER
            }

            if (cleanStatus.equals("REJECTED", ignoreCase = true) ||
                cleanStatus.equals("FAILED", ignoreCase = true) ||
                cleanStatus.equals("INVALID", ignoreCase = true)
            ) {
                return UtrSnapshotDecision.REJECTED
            }

            if (cleanStatus.equals("Verified", ignoreCase = true)) {
                return if (!isUsed) {
                    UtrSnapshotDecision.VERIFIED_READY_TO_CLAIM
                } else if (cleanClaimedBy.isEmpty() || matchesCurrentUser(cleanClaimedBy)) {
                    if (cleanOwner.isNotEmpty() && !matchesCurrentUser(cleanOwner) && cleanClaimedBy.isEmpty()) {
                        UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER
                    } else {
                        UtrSnapshotDecision.APPROVED_ALREADY_APPLIED
                    }
                } else {
                    UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER
                }
            }

            if (cleanStatus.equals("APPROVED", ignoreCase = true) ||
                cleanStatus.equals("SUCCESS", ignoreCase = true)
            ) {
                return if (cleanClaimedBy.isEmpty() || matchesCurrentUser(cleanClaimedBy)) {
                    if (cleanOwner.isNotEmpty() && !matchesCurrentUser(cleanOwner) && cleanClaimedBy.isEmpty()) {
                        UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER
                    } else {
                        UtrSnapshotDecision.APPROVED_ALREADY_APPLIED
                    }
                } else {
                    UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER
                }
            }

            if (isUsed) {
                return UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER
            }

            return UtrSnapshotDecision.PENDING
        }

        /**
         * Resolves the authoritative post-activation expiry timestamp cleanly by tier type:
         * - Pro Passes (`DAILY`, `WEEKLY`, `MONTHLY`): reads `serverExpiry` (for a Pro tier) or `activePassExpiry`.
         * - `REMOVE_ADS`: strictly reads `serverExpiry` (when `serverTier == REMOVE_ADS`) or `removeAdsExpiry`,
         *   never falling back to Pro `activePassInfo`, falling back to `now + tier.durationMs` only if `<= now`.
         */
        @VisibleForTesting
        internal fun resolveActivatedExpiryTimestamp(
            tier: PassTier,
            serverTier: PassTier?,
            serverExpiry: Long,
            activePassExpiry: Long,
            removeAdsExpiry: Long,
            now: Long = LicenseManager.getCurrentServerTimeMs()
        ): Long {
            if (!tier.isProPass) {
                return when {
                    serverTier == PassTier.REMOVE_ADS && serverExpiry > now -> serverExpiry
                    removeAdsExpiry > now -> removeAdsExpiry
                    else -> now + tier.durationMs
                }
            }
            return when {
                serverTier?.isProPass == true && serverExpiry > now -> serverExpiry
                activePassExpiry > now -> activePassExpiry
                else -> now + tier.durationMs
            }
        }

        /**
         * Builds the canonical `pending_verifications/{utr}` document payload using canonical `uid`
         * and `firebaseUid` (no duplicate `userId` field), strictly matching unified `firestore.rules`.
         */
        @VisibleForTesting
        internal fun buildPendingVerificationPayload(
            utr: String,
            firebaseUid: String,
            tier: PassTier,
            deviceHardwareId: String,
            createdAtValue: Any = FieldValue.serverTimestamp()
        ): Map<String, Any> {
            return mapOf(
                "status" to "PENDING",
                "passName" to tier.title,
                "passTier" to tier.id,
                "amount" to tier.effectivePriceInInr(),
                "isUsed" to false,
                "utr" to utr,
                "uid" to firebaseUid,
                "firebaseUid" to firebaseUid,
                "deviceHardwareId" to deviceHardwareId,
                "createdAt" to createdAtValue
            )
        }

        val defaultInstance by lazy { PaymentVerificationRepository() }

        private const val FIRESTORE_COLLECTION_ADMIN_SETTINGS = "admin_settings"
        private const val FIRESTORE_DOC_PAYMENT_CONFIG = "payment_config"

        /**
         * Fetches dynamic payment config (Admin UPI ID, Payee Name, and live pricing overrides)
         * from Firestore `admin_settings/payment_config` both pre-login and post-login,
         * guarded by `AppConfigRepository.canReadAdminSetting("payment_config", currentUid)`.
         */
        suspend fun fetchPaymentConfig(context: Context? = null): Pair<String, String> {
            return try {
                withTimeoutOrNull(3500L) {
                    val isInitialized = if (context != null) {
                        FirebaseApp.getApps(context).isNotEmpty()
                    } else {
                        try {
                            FirebaseApp.getInstance()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (!isInitialized) {
                        Log.w(TAG, "FirebaseApp is not initialized; cannot fetch payment config.")
                        return@withTimeoutOrNull Pair("", "")
                    }
                    val currentUid = try {
                        FirebaseAuth.getInstance().currentUser?.uid
                    } catch (_: Exception) {
                        null
                    }
                    if (!AppConfigRepository.canReadAdminSetting(FIRESTORE_DOC_PAYMENT_CONFIG, currentUid)) {
                        Log.d(TAG, "Read not permitted for admin_settings/payment_config; skipping fetch.")
                        return@withTimeoutOrNull Pair("", "")
                    }
                    val firestore = FirebaseFirestore.getInstance()
                    val docSnapshot = firestore.collection(FIRESTORE_COLLECTION_ADMIN_SETTINGS)
                        .document(FIRESTORE_DOC_PAYMENT_CONFIG)
                        .get()
                        .await()

                    if (docSnapshot.exists()) {
                        val config = AppConfigRepository.applyPaymentConfigFromSnapshot(docSnapshot)
                        Log.d(TAG, "Successfully fetched payment config: upi=${config.adminUpiId}, payee=${config.adminPayeeName}")
                        Pair(config.adminUpiId, config.adminPayeeName)
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

        /**
         * Convenience static method to inspect Firestore /users/{userId} for paymentStatus.
         */
        suspend fun checkUserPaymentStatus(context: Context): Boolean {
            return defaultInstance.checkIsPaymentVerified(context)
        }
    }

    /**
     * Checks if active network connectivity is available via centralized DeviceUtils.
     */
    fun isNetworkAvailable(context: Context): Boolean = DeviceUtils.isNetworkAvailable(context)

    /**
     * Checks Firestore user profile `/users/{userId}` for active pass status using canonical fields:
     * `activePassType`, `passExpiryTimestamp`, and `paymentStatus`.
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
            val doc = LicenseManager.resolveUserDocumentSnapshot(firestore, userId)
            if (doc != null && doc.exists()) {
                val passTypeStr = doc.getString("activePassType")
                val parsedTier = PassTier.fromId(passTypeStr)
                val isProTier = parsedTier?.isProPass == true
                val paymentStatus = doc.getString("paymentStatus")
                    ?: if (!passTypeStr.isNullOrBlank() && isProTier) "SUCCESS" else "FAILED"

                val passExpiryDate = doc.getLong("passExpiryTimestamp") ?: 0L

                val currentTime = LicenseManager.getCurrentServerTimeMs()
                val isPaymentSuccessful = paymentStatus.equals("SUCCESS", ignoreCase = true) ||
                    paymentStatus.equals("VERIFIED", ignoreCase = true) ||
                    paymentStatus.equals("APPROVED", ignoreCase = true)
                val isActive = isProTier && isPaymentSuccessful && passExpiryDate > currentTime

                LicenseManager.refreshAccessStatus(context)
                Log.d(TAG, "checkIsPaymentVerified for user $userId: isActive=$isActive (status=$paymentStatus, expiry=$passExpiryDate)")
                isActive
            } else {
                Log.d(TAG, "checkIsPaymentVerified for user $userId: user doc does not exist")
                LicenseManager.refreshAccessStatus(context)
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
     * Strictly delegates to LicenseManager.resolveUserKey to ensure a single, consistent Document ID.
     */
    fun resolveUserId(context: Context): String {
        return LicenseManager.resolveUserKey(context)
    }

    /**
     * Initiates the UI-driven payment verification flow for a given 12-digit UTR.
     * Relies exclusively on `pending_verifications/{utr}` and `pending_actions` (`ACTIVATE_PASS_PAYMENT`).
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

        // 2. Require explicit Pass plan selection to prevent silent amount=9 mismatch
        val requiredTier: PassTier = selectedTier ?: run {
            _verificationState.value = PaymentVerificationState.Error(
                message = "Please select the Pass plan you paid for before submitting your UTR.",
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
                val pendingDocRef = firestore.collection(COLL_PENDING_VERIFICATIONS).document(utr)

                val pendingSnapshot = try {
                    pendingDocRef.get().await()
                } catch (_: Exception) {
                    null
                }

                val existingSnap = pendingSnapshot?.takeIf { it.exists() }

                suspend fun handleApprovedAlreadyApplied(
                    utrStr: String,
                    amountVal: Long,
                    docTierId: String?,
                    isImmediate: Boolean
                ) {
                    val (serverTier, serverExpiry) = LicenseManager.refreshUserPassStatusFromServer(context)
                    val resolvedTier: PassTier = if (!requiredTier.isProPass) {
                        PassTier.fromId(docTierId) ?: requiredTier
                    } else {
                        serverTier ?: PassTier.fromId(docTierId) ?: requiredTier
                    }
                    val now = LicenseManager.getCurrentServerTimeMs()
                    val resolvedExpiry = resolveActivatedExpiryTimestamp(
                        tier = resolvedTier,
                        serverTier = serverTier,
                        serverExpiry = serverExpiry,
                        activePassExpiry = AppSettings.getPassExpiryTimestamp(context),
                        removeAdsExpiry = LicenseManager.removeAdsExpiryMs.value,
                        now = now
                    )
                    withContext(NonCancellable + Dispatchers.Main) {
                        AppSettings.addLog(
                            title = "Payment Approved & Synced",
                            message = "UTR: $utrStr (₹$amountVal). ${resolvedTier.title} confirmed active.",
                            severity = LogSeverity.INFO
                        )
                    }
                    cancelPendingListener()
                    _verificationState.value = PaymentVerificationState.Success(
                        utr = utrStr,
                        amount = amountVal,
                        passTier = resolvedTier,
                        expiryTimestamp = resolvedExpiry,
                        isImmediate = isImmediate,
                        message = "Payment of ₹$amountVal verified successfully! ${resolvedTier.title} is now active."
                    )
                }

                if (existingSnap != null) {
                    val isUsed = existingSnap.getBoolean("isUsed") ?: false
                    val status = existingSnap.getString("status") ?: ""
                    val amount = existingSnap.getLong("amount")
                        ?: requiredTier.effectivePriceInInr().toLong()
                    val claimedBy = existingSnap.getString("claimedByUserId")
                        ?: existingSnap.getString("claimedBy")
                    val docOwnerId = existingSnap.getString("uid")
                        ?: existingSnap.getString("firebaseUid")
                        ?: existingSnap.getString("userId")
                    val docTierId = existingSnap.getString("passTier")

                    when (evaluateUtrSnapshot(status, isUsed, claimedBy, docOwnerId, userId, currentUid)) {
                        UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER -> {
                            _verificationState.value = PaymentVerificationState.Error(
                                message = "This UTR ($utr) has already been claimed on another account.",
                                canRetry = false,
                                utr = utr
                            )
                            return@launch
                        }
                        UtrSnapshotDecision.VERIFIED_READY_TO_CLAIM -> {
                            executeClaimAndUnlock(
                                context = context,
                                utr = utr,
                                amount = amount,
                                userId = userId,
                                isImmediate = true,
                                preferredTier = requiredTier,
                                firebaseUid = currentUid
                            )
                            return@launch
                        }
                        UtrSnapshotDecision.APPROVED_ALREADY_APPLIED -> {
                            handleApprovedAlreadyApplied(
                                utrStr = utr,
                                amountVal = amount,
                                docTierId = docTierId,
                                isImmediate = true
                            )
                            return@launch
                        }
                        UtrSnapshotDecision.REJECTED -> {
                            val rawErr = existingSnap.getString("errorMessage")
                                ?: existingSnap.getString("reason")
                                ?: "Payment verification for UTR $utr was rejected."
                            val errCode = existingSnap.getString("errorCode")
                            val decision = classifyVerificationRejection(rawErr, errCode)
                            if (!decision.isTerminal && decision.isStillPending) {
                                applyRejectionDecision(utr, decision)
                                attachRealtimePaymentListener(
                                    context = context,
                                    utr = utr,
                                    userId = userId,
                                    firebaseUid = currentUid,
                                    preferredTier = selectedTier
                                )
                            } else {
                                applyRejectionDecision(utr, decision)
                            }
                            return@launch
                        }
                        UtrSnapshotDecision.PENDING -> Unit
                    }
                }

                // Register pending verification only if it does not already exist (Zero-Trust create rule)
                if (pendingSnapshot == null || !pendingSnapshot.exists()) {
                    val deviceHardwareId = HardwareIdManager.getPermanentHardwareId(context)
                    val pendingPayload = buildPendingVerificationPayload(
                        utr = utr,
                        firebaseUid = currentUid,
                        tier = requiredTier,
                        deviceHardwareId = deviceHardwareId
                    )
                    pendingDocRef.set(pendingPayload).await()
                }

                _verificationState.value = PaymentVerificationState.Pending(
                    utr = utr,
                    message = "UTR submitted. Waiting for bank SMS confirmation — your pass will activate automatically once received."
                )

                attachRealtimePaymentListener(
                    context = context,
                    utr = utr,
                    userId = userId,
                    firebaseUid = currentUid,
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

    private sealed interface RealtimeWaitOutcome {
        data object VerifiedReadyToClaim : RealtimeWaitOutcome
        data class ApprovedAlreadyApplied(val amount: Long, val passTierId: String?) : RealtimeWaitOutcome
        data class EarlyResult(val result: PassManager.VerificationResult) : RealtimeWaitOutcome
    }

    /**
     * Canonical suspend verification & real-time waiting pipeline for a 12-digit UTR.
     * Used by PassManager.verifyPaymentWithBackendDetailed so UTR verification logic is not duplicated.
     */
    suspend fun verifyUtrDetailed(
        context: Context,
        utr: String,
        preferredTier: PassTier? = null
    ): PassManager.VerificationResult = withContext(Dispatchers.IO) {
        val cleanUtr = utr.trim()
        if (!isValidUtr(cleanUtr)) {
            return@withContext PassManager.VerificationResult.Invalid(
                "Please enter a valid 12-digit numeric UTR / UPI reference number."
            )
        }

        if (!PassManager.checkAndRecordUtrRateLimit(context)) {
            Log.w(TAG, "UTR verification rate limit exceeded for attempt on $cleanUtr")
            return@withContext PassManager.VerificationResult.Error(
                "Too many attempts. Please try again after 1 minute."
            )
        }

        if (preferredTier == null) {
            return@withContext PassManager.VerificationResult.Invalid(
                "Please select the Pass plan you paid for before verifying your UTR."
            )
        }

        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Log.w(TAG, "FirebaseApp is not initialized; cannot verify payment.")
                return@withContext PassManager.VerificationResult.Error("Firebase service is not initialized.")
            }
            val authUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (_: Exception) {
                null
            }
            val firebaseUid = authUser?.uid?.takeIf { it.isNotBlank() }
                ?: return@withContext PassManager.VerificationResult.Error("Please sign in with Google to verify payment.")
            val userId = resolveUserId(context)
            if (userId.isBlank()) {
                return@withContext PassManager.VerificationResult.Error("Please sign in with Google to verify payment.")
            }
            val pendingDocRef = firestore.collection(COLL_PENDING_VERIFICATIONS).document(cleanUtr)
            val tier = preferredTier

            val pendingSnapshot = try {
                pendingDocRef.get().await()
            } catch (e: Exception) {
                Log.w(TAG, "Direct get on pending_verifications failed: ${e.message}")
                null
            }

            val existingSnap = pendingSnapshot?.takeIf { it.exists() }

            suspend fun completeApprovedPassResult(docTierId: String?): PassManager.VerificationResult {
                val (serverTier, serverExpiry) = LicenseManager.refreshUserPassStatusFromServer(context)
                val resolvedTier = if (!tier.isProPass) {
                    PassTier.fromId(docTierId) ?: tier
                } else {
                    serverTier ?: PassTier.fromId(docTierId) ?: tier
                }
                val now = LicenseManager.getCurrentServerTimeMs()
                val resolvedExpiry = resolveActivatedExpiryTimestamp(
                    tier = resolvedTier,
                    serverTier = serverTier,
                    serverExpiry = serverExpiry,
                    activePassExpiry = LicenseManager.activePassInfo.value?.expiryTimestamp ?: 0L,
                    removeAdsExpiry = LicenseManager.removeAdsExpiryMs.value,
                    now = now
                )
                withContext(NonCancellable + Dispatchers.Main) {
                    AppSettings.addLog(
                        title = "Pass Approved & Synced",
                        message = "${resolvedTier.title} verified via UTR $cleanUtr.",
                        severity = LogSeverity.MATCH_ACCEPTED
                    )
                }
                return PassManager.VerificationResult.Success(resolvedTier, resolvedExpiry)
            }

            if (existingSnap != null) {
                val status = existingSnap.getString("status") ?: ""
                val isUsed = existingSnap.getBoolean("isUsed") ?: false
                val claimedBy = existingSnap.getString("claimedByUserId")
                    ?: existingSnap.getString("claimedBy")
                val docOwnerId = existingSnap.getString("uid")
                    ?: existingSnap.getString("firebaseUid")
                    ?: existingSnap.getString("userId")

                when (evaluateUtrSnapshot(status, isUsed, claimedBy, docOwnerId, userId, firebaseUid)) {
                    UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER -> {
                        return@withContext PassManager.VerificationResult.AlreadyUsed(
                            "This UTR has already been claimed on another account."
                        )
                    }
                    UtrSnapshotDecision.VERIFIED_READY_TO_CLAIM -> {
                        return@withContext executeSuspendClaimTransaction(
                            cleanUtr = cleanUtr,
                            preferredTier = tier,
                            context = context
                        )
                    }
                    UtrSnapshotDecision.APPROVED_ALREADY_APPLIED -> {
                        return@withContext completeApprovedPassResult(
                            existingSnap.getString("passTier")
                        )
                    }
                    UtrSnapshotDecision.REJECTED -> {
                        val rawErr = existingSnap.getString("errorMessage")
                            ?: existingSnap.getString("reason")
                            ?: "Payment verification was rejected. Please check your UTR."
                        val errCode = existingSnap.getString("errorCode")
                        val decision = classifyVerificationRejection(rawErr, errCode)
                        if (decision.isAlreadyClaimed) {
                            return@withContext PassManager.VerificationResult.AlreadyUsed(decision.message)
                        }
                        if (!decision.isTerminal && decision.isStillPending) {
                            // Continue waiting on pending_verifications/{utr} until bank SMS arrives.
                        } else {
                            return@withContext PassManager.VerificationResult.Error(decision.message)
                        }
                    }
                    UtrSnapshotDecision.PENDING -> Unit
                }
            }

            if (pendingSnapshot == null || !pendingSnapshot.exists()) {
                val deviceHardwareId = HardwareIdManager.getPermanentHardwareId(context)
                try {
                    pendingDocRef.set(
                        buildPendingVerificationPayload(
                            utr = cleanUtr,
                            firebaseUid = firebaseUid,
                            tier = tier,
                            deviceHardwareId = deviceHardwareId
                        )
                    ).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Recording pending verification ignored or suppressed: ${e.message}")
                }
            }

            val waitOutcome = withTimeoutOrNull(120_000L) {
                suspendCancellableCoroutine<RealtimeWaitOutcome> { continuation ->
                    var pendingListener: ListenerRegistration? = null
                    val isResumed = AtomicBoolean(false)

                    fun removeListeners() {
                        pendingListener?.remove()
                        pendingListener = null
                    }

                    fun handleSnapshot(docSnap: DocumentSnapshot?) {
                        if (docSnap != null && docSnap.exists()) {
                            val currentStatus = docSnap.getString("status") ?: ""
                            val currentUsed = docSnap.getBoolean("isUsed") ?: false
                            val currentClaimedBy = docSnap.getString("claimedByUserId")
                                ?: docSnap.getString("claimedBy")
                            val docOwnerId = docSnap.getString("uid")
                                ?: docSnap.getString("firebaseUid")
                                ?: docSnap.getString("userId")

                            when (evaluateUtrSnapshot(currentStatus, currentUsed, currentClaimedBy, docOwnerId, userId, firebaseUid)) {
                                UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER -> {
                                    if (isResumed.compareAndSet(false, true)) {
                                        removeListeners()
                                        continuation.resume(
                                            RealtimeWaitOutcome.EarlyResult(
                                                PassManager.VerificationResult.AlreadyUsed(
                                                    "This UTR has already been claimed on another account."
                                                )
                                            )
                                        )
                                    }
                                }
                                UtrSnapshotDecision.REJECTED -> {
                                    val rawErr = docSnap.getString("errorMessage")
                                        ?: docSnap.getString("reason")
                                        ?: "Payment verification was rejected. Please check your UTR."
                                    val errCode = docSnap.getString("errorCode")
                                    val decision = classifyVerificationRejection(rawErr, errCode)
                                    if (!decision.isTerminal && decision.isStillPending) {
                                        // Keep pending_verifications/{utr} listener alive for bank SMS auto-approval.
                                    } else if (isResumed.compareAndSet(false, true)) {
                                        removeListeners()
                                        val early = if (decision.isAlreadyClaimed) {
                                            PassManager.VerificationResult.AlreadyUsed(decision.message)
                                        } else {
                                            PassManager.VerificationResult.Error(decision.message)
                                        }
                                        continuation.resume(RealtimeWaitOutcome.EarlyResult(early))
                                    }
                                }
                                UtrSnapshotDecision.VERIFIED_READY_TO_CLAIM -> {
                                    if (isResumed.compareAndSet(false, true)) {
                                        removeListeners()
                                        continuation.resume(RealtimeWaitOutcome.VerifiedReadyToClaim)
                                    }
                                }
                                UtrSnapshotDecision.APPROVED_ALREADY_APPLIED -> {
                                    if (isResumed.compareAndSet(false, true)) {
                                        removeListeners()
                                        val amt = docSnap.getLong("amount") ?: tier.effectivePriceInInr().toLong()
                                        val tierId = docSnap.getString("passTier")
                                        continuation.resume(RealtimeWaitOutcome.ApprovedAlreadyApplied(amt, tierId))
                                    }
                                }
                                UtrSnapshotDecision.PENDING -> Unit
                            }
                        }
                    }

                    pendingListener = pendingDocRef.addSnapshotListener { docSnap, error ->
                        if (error != null) {
                            Log.w(TAG, "Real-time pending snapshot listener error for $cleanUtr: ${error.message}")
                            return@addSnapshotListener
                        }
                        handleSnapshot(docSnap)
                    }

                    continuation.invokeOnCancellation {
                        removeListeners()
                    }
                }
            }

            when (waitOutcome) {
                is RealtimeWaitOutcome.VerifiedReadyToClaim -> {
                    executeSuspendClaimTransaction(
                        cleanUtr = cleanUtr,
                        preferredTier = tier,
                        context = context
                    )
                }
                is RealtimeWaitOutcome.ApprovedAlreadyApplied -> {
                    completeApprovedPassResult(waitOutcome.passTierId)
                }
                is RealtimeWaitOutcome.EarlyResult -> {
                    waitOutcome.result
                }
                null -> {
                    PassManager.VerificationResult.NotFound(
                        "Verification timed out after 2 minutes. Please check your UTR or try again later."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyUtrDetailed failed for $cleanUtr: ${e.message}", e)
            val msg = e.localizedMessage ?: "Network or verification error. Please retry."
            PassManager.VerificationResult.Error("Error verifying payment: $msg")
        }
    }

    private suspend fun executeSuspendClaimTransaction(
        cleanUtr: String,
        preferredTier: PassTier?,
        context: Context
    ): PassManager.VerificationResult = withContext(Dispatchers.IO) {
        val tier = preferredTier
            ?: return@withContext PassManager.VerificationResult.Invalid(
                "Please select the Pass plan you paid for before verifying your UTR."
            )
        fun mapFailureMessage(msg: String): PassManager.VerificationResult {
            val decision = classifyVerificationRejection(msg)
            return when {
                decision.isAlreadyClaimed -> {
                    PassManager.VerificationResult.AlreadyUsed(decision.message)
                }
                decision.isStillPending -> {
                    PassManager.VerificationResult.NotFound(decision.message)
                }
                else -> PassManager.VerificationResult.Error(decision.message)
            }
        }
        try {
            val result = LicenseManager.activatePassViaPayment(tier, cleanUtr)
            if (result.isSuccess) {
                val (serverTier, serverExpiry) = LicenseManager.refreshUserPassStatusFromServer(context)
                val now = LicenseManager.getCurrentServerTimeMs()
                val finalExpiryTimestamp = resolveActivatedExpiryTimestamp(
                    tier = tier,
                    serverTier = serverTier,
                    serverExpiry = serverExpiry,
                    activePassExpiry = LicenseManager.activePassInfo.value?.expiryTimestamp ?: 0L,
                    removeAdsExpiry = LicenseManager.removeAdsExpiryMs.value,
                    now = now
                )

                withContext(NonCancellable + Dispatchers.Main) {
                    AppSettings.addLog(
                        title = "Pass Activated",
                        message = "${tier.title} activated via UTR $cleanUtr. Valid for ${tier.durationDays} day(s).",
                        severity = LogSeverity.MATCH_ACCEPTED
                    )
                }

                PassManager.VerificationResult.Success(tier, finalExpiryTimestamp)
            } else {
                val err = result.exceptionOrNull()
                val msg = err?.message ?: "Action Queue verification failed"
                mapFailureMessage(msg)
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val mapped = mapFailureMessage(msg)
            if (mapped is PassManager.VerificationResult.Error) {
                Log.e(TAG, "Zero-Trust Action Queue claim failed: $msg", e)
                PassManager.VerificationResult.Error("Verification failed: $msg")
            } else {
                mapped
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
        firebaseUid: String,
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
                val amount = snapshot.getLong("amount")
                    ?: (preferredTier?.effectivePriceInInr()?.toLong() ?: 0L)
                val claimedBy = snapshot.getString("claimedByUserId")
                    ?: snapshot.getString("claimedBy")
                val docOwnerId = snapshot.getString("uid")
                    ?: snapshot.getString("firebaseUid")
                    ?: snapshot.getString("userId")
                val docTierId = snapshot.getString("passTier")

                Log.i(TAG, "Realtime update received for UTR $utr: status=$status, isUsed=$isUsed, amount=$amount")

                when (evaluateUtrSnapshot(status, isUsed, claimedBy, docOwnerId, userId, firebaseUid)) {
                    UtrSnapshotDecision.VERIFIED_READY_TO_CLAIM -> {
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
                    }
                    UtrSnapshotDecision.APPROVED_ALREADY_APPLIED -> {
                        cancelPendingListener()
                        repoScope.launch {
                            val (serverTier, serverExpiry) = LicenseManager.refreshUserPassStatusFromServer(context)
                            val docOrPrefTier = PassTier.fromId(docTierId) ?: preferredTier
                            val resolvedTier = if (docOrPrefTier != null && !docOrPrefTier.isProPass) {
                                docOrPrefTier
                            } else {
                                serverTier ?: docOrPrefTier ?: mapAmountToPassTier(amount)
                            }
                            if (resolvedTier == null) {
                                _verificationState.value = PaymentVerificationState.Error(
                                    message = "Could not determine Pass plan for ₹$amount. Please select your Pass plan and retry.",
                                    canRetry = true,
                                    utr = utr
                                )
                                return@launch
                            }
                            val now = LicenseManager.getCurrentServerTimeMs()
                            val resolvedExpiry = resolveActivatedExpiryTimestamp(
                                tier = resolvedTier,
                                serverTier = serverTier,
                                serverExpiry = serverExpiry,
                                activePassExpiry = AppSettings.getPassExpiryTimestamp(context),
                                removeAdsExpiry = LicenseManager.removeAdsExpiryMs.value,
                                now = now
                            )
                            withContext(NonCancellable + Dispatchers.Main) {
                                AppSettings.addLog(
                                    title = "Payment Approved & Synced",
                                    message = "UTR: $utr (₹$amount). ${resolvedTier.title} confirmed active.",
                                    severity = LogSeverity.INFO
                                )
                            }
                            _verificationState.value = PaymentVerificationState.Success(
                                utr = utr,
                                amount = amount,
                                passTier = resolvedTier,
                                expiryTimestamp = resolvedExpiry,
                                isImmediate = false,
                                message = "Payment of ₹$amount verified successfully! ${resolvedTier.title} is now active."
                            )
                        }
                    }
                    UtrSnapshotDecision.REJECTED -> {
                        val rawErr = snapshot.getString("errorMessage")
                            ?: snapshot.getString("reason")
                            ?: "Payment verification for UTR $utr was rejected."
                        val errCode = snapshot.getString("errorCode")
                        val decision = classifyVerificationRejection(rawErr, errCode)
                        applyRejectionDecision(utr, decision)
                    }
                    UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER -> {
                        _verificationState.value = PaymentVerificationState.Error(
                            message = "This UTR ($utr) has already been claimed on another account.",
                            canRetry = false,
                            utr = utr
                        )
                        cancelPendingListener()
                    }
                    UtrSnapshotDecision.PENDING -> Unit
                }
            }
        }
    }

    /**
     * Applies a classified rejection decision consistently across both the Action Queue claim path
     * and the `pending_verifications/{utr}` snapshot listener:
     * - Non-terminal pending decisions (e.g., `PAYMENT_NOT_RECEIVED_YET`) keep the real-time
     *   listener alive and maintain `PaymentVerificationState.Pending` with the waiting message.
     * - Terminal rejections (e.g., `UTR_ALREADY_USED`, `AMOUNT_MISMATCH`, untrusted signature)
     *   immediately cancel the active listener and emit `PaymentVerificationState.Error`.
     */
    @VisibleForTesting
    internal fun applyRejectionDecision(
        utr: String,
        decision: VerificationRejectionDecision
    ) {
        if (!decision.isTerminal && decision.isStillPending) {
            _verificationState.value = PaymentVerificationState.Pending(
                utr = utr,
                message = decision.message
            )
            return
        }
        if (decision.isTerminal) {
            cancelPendingListener()
        }
        _verificationState.value = PaymentVerificationState.Error(
            message = decision.message,
            canRetry = decision.canRetry,
            utr = utr
        )
    }

    @VisibleForTesting
    internal fun attachListenerForTesting(utr: String, registration: ListenerRegistration) {
        cancelPendingListener()
        activeListeningUtr = utr
        realtimeListener = registration
    }

    @VisibleForTesting
    internal fun hasActiveListenerForTesting(): Boolean = realtimeListener != null

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
        preferredTier: PassTier?,
        firebaseUid: String = userId
    ) {
        try {
            val passTier = preferredTier ?: mapAmountToPassTier(amount)
            if (passTier == null) {
                cancelPendingListener()
                _verificationState.value = PaymentVerificationState.Error(
                    message = "Invalid payment amount (₹$amount). Please select the Pass plan you paid for and retry.",
                    canRetry = true,
                    utr = utr
                )
                return
            }
            val actionResult = LicenseManager.activatePassViaPayment(passTier, utr)
            if (actionResult.isFailure) {
                val rawErrMsg = actionResult.exceptionOrNull()?.message ?: "Payment could not be verified by server."
                val decision = classifyVerificationRejection(rawErrMsg)
                applyRejectionDecision(utr, decision)
                if (!decision.isTerminal && decision.isStillPending && realtimeListener == null) {
                    attachRealtimePaymentListener(
                        context = context,
                        utr = utr,
                        userId = userId,
                        firebaseUid = firebaseUid,
                        preferredTier = passTier
                    )
                }
                return
            }

            val (serverTier, serverExpiry) = LicenseManager.refreshUserPassStatusFromServer(context)
            val now = LicenseManager.getCurrentServerTimeMs()
            val newExpiryTimestamp = resolveActivatedExpiryTimestamp(
                tier = passTier,
                serverTier = serverTier,
                serverExpiry = serverExpiry,
                activePassExpiry = AppSettings.getPassExpiryTimestamp(context),
                removeAdsExpiry = LicenseManager.removeAdsExpiryMs.value,
                now = now
            )
            val newExpiryDate = Date(newExpiryTimestamp)

            withContext(NonCancellable + Dispatchers.Main) {
                AppSettings.addLog(
                    title = "Payment Verified & Unlocked",
                    message = "UTR: $utr (₹$amount). Activated ${passTier.title} until $newExpiryDate.",
                    severity = LogSeverity.INFO
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
