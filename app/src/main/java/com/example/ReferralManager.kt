package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ReferralManager
 *
 * Implements a zero-trust, server-enforced Refer & Earn system via Vercel Serverless API:
 * 1. Validate input (length 8, not empty).
 * 2. Block Self-Referral: Return failure if submittedCode equals LicenseManager.referralCode.value.
 * 3. Atomic Server Transaction (claimReferralCode Vercel API endpoint):
 *    - Validates code on backend.
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
     * Executes the atomic referral claim transaction via Vercel API.
     */
    suspend fun claimReferralCode(context: Context, submittedCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanCode = submittedCode.trim().uppercase(Locale.ROOT)

        // 1. Validate input
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

        try {
            val json = VercelApiClient.post("claimReferralCode", mapOf("submittedCode" to cleanCode))
            val newPoints = json.optInt("newPointsBalance", LicenseManager.pointsBalance.value + WELCOME_BONUS_POINTS)

            // Update in-memory reactive state on success
            LicenseManager.notifyReferralClaimed(newPoints)
            pendingInviteCode.value = ""
            Log.i(TAG, "Referral claimed via Vercel API: referee +50 pts, referrer +20 pts")
            Result.success(Unit)
        } catch (e: Exception) {
            val message = when {
                e.message?.contains("Cannot use your own", ignoreCase = true) == true -> "Cannot use your own code"
                e.message?.contains("Bonus Already Claimed", ignoreCase = true) == true -> "Bonus Already Claimed"
                e.message?.contains("Invalid referral code", ignoreCase = true) == true ||
                e.message?.contains("not found", ignoreCase = true) == true -> "Invalid Code"
                e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ->
                    "Server security policy prevented referral claim. Please try again."
                else -> e.message ?: "Failed to process referral code. Please check your connection."
            }
            Log.e(TAG, "Referral transaction failed: $message", e)
            Result.failure(Exception(message, e))
        }
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
