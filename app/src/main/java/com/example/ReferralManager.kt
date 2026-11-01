package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ReferralManager
 *
 * Implements direct client-side Refer & Earn system via Firebase Firestore SDK:
 * 1. Validate input (length 8, not empty).
 * 2. Block Self-Referral: Return failure if submittedCode equals LicenseManager.referralCode.value.
 * 3. Atomic Firestore Transaction:
 *    - Validates code on users_licensing collection.
 *    - Strictly checks hasClaimedReferral == false.
 *    - Updates referrer (+20 pts, +1 count) and referee (+50 pts).
 * 4. Catch exceptions and return clean Result.failure strings.
 */
object ReferralManager {

    private const val TAG = "ReferralManager"

    const val REFERRAL_BONUS_POINTS = 20
    const val WELCOME_BONUS_POINTS = 50

    // Deep link captured invite code
    val pendingInviteCode = MutableStateFlow<String>("")

    /**
     * Executes the referral claim via the Zero-Trust Action Queue.
     * Enqueues CLAIM_REFERRAL to `pending_actions` and awaits server completion.
     */
    suspend fun claimReferralCode(
        context: Context,
        submittedCode: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanCode = submittedCode.trim().uppercase(Locale.ROOT)

        // 1. Client Validation
        if (cleanCode.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Referral code cannot be empty."))
        }

        if (cleanCode.length != 8) {
            return@withContext Result.failure(IllegalArgumentException("Referral code must be exactly 8 characters."))
        }

        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Please sign in with Google to claim referral rewards."))
        }

        // 2. Block Self-Referral against local user referralCode
        val myCode = LicenseManager.referralCode.value
        if (!myCode.isNullOrBlank() && myCode.equals(cleanCode, ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Cannot use your own code"))
        }

        if (LicenseManager.hasClaimedReferral.value) {
            return@withContext Result.failure(IllegalStateException("Bonus Already Claimed"))
        }

        // 3. Dispatch to Zero-Trust Action Queue
        val actionResult = LicenseManager.executeActionQueueRequest(
            actionType = "CLAIM_REFERRAL",
            payload = mapOf(
                "referralCode" to cleanCode
            )
        )

        actionResult.fold(
            onSuccess = { snap ->
                val newPoints = snap.getLong("newBalance")?.toInt()
                    ?: (LicenseManager.pointsBalance.value + WELCOME_BONUS_POINTS)
                LicenseManager.notifyReferralClaimed(newPoints)
                pendingInviteCode.value = ""
                Log.i(TAG, "CLAIM_REFERRAL completed via Action Queue.")
                Result.success(Unit)
            },
            onFailure = { e ->
                val message = when {
                    e.message?.contains("Cannot use your own", ignoreCase = true) == true -> "Cannot use your own code"
                    e.message?.contains("Bonus Already Claimed", ignoreCase = true) == true -> "Bonus Already Claimed"
                    e.message?.contains("Invalid referral code", ignoreCase = true) == true ||
                    e.message?.contains("Invalid Code", ignoreCase = true) == true ||
                    e.message?.contains("not found", ignoreCase = true) == true -> "Invalid Code"
                    e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ->
                        "Server security policy prevented referral claim. Please try again."
                    else -> e.message ?: "Failed to process referral code. Please check your connection."
                }
                Log.e(TAG, "Referral claim failed: $message", e)
                Result.failure(Exception(message, e))
            }
        )
    }

    /**
     * Overload for claimReferralCode without requiring explicit context.
     */
    suspend fun claimReferralCode(submittedCode: String): Result<Unit> {
        val appCtx = LicenseManager.getAppContext()
        val dummyCtx = appCtx ?: FirebaseAuth.getInstance().app.applicationContext
        return claimReferralCode(dummyCtx, submittedCode)
    }
}
