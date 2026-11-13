package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ReferralManager
 *
 * Zero-Trust Refer & Earn client coordinator:
 * - Client never queries `users` across accounts or mutates points/referrals directly.
 * - Validates input format and dispatches `CLAIM_REFERRAL` through `pending_actions`.
 */
object ReferralManager {

    private const val TAG = "ReferralManager"

    const val REFERRAL_BONUS_POINTS = 20
    const val WELCOME_BONUS_POINTS = 50

    // Deep link captured invite code
    val pendingInviteCode = MutableStateFlow<String>("")

    /**
     * Executes referral claiming via the Zero-Trust `CLAIM_REFERRAL` action queue.
     */
    suspend fun claimReferralCode(
        context: Context,
        submittedCode: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanCode = submittedCode.trim().uppercase(Locale.ROOT)

        if (cleanCode.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Referral code cannot be empty."))
        }
        if (cleanCode.length != 8) {
            return@withContext Result.failure(IllegalArgumentException("Referral code must be exactly 8 characters."))
        }

        if (FirebaseApp.getApps(context).isEmpty()) {
            try {
                FirebaseApp.initializeApp(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed initializing FirebaseApp: ${e.message}")
            }
        }

        val currentUser = try {
            if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseAuth.getInstance().currentUser else null
        } catch (_: Exception) {
            null
        }
        if (currentUser == null || currentUser.uid.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Please sign in with Google to claim referral rewards."))
        }

        val myCode = LicenseManager.referralCode.value
        if (!myCode.isNullOrBlank() && myCode.equals(cleanCode, ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Cannot use your own code"))
        }

        if (LicenseManager.hasClaimedReferral.value) {
            return@withContext Result.failure(IllegalStateException("Bonus Already Claimed"))
        }

        val queueResult = LicenseManager.claimReferralViaActionQueue(cleanCode)
        queueResult.fold(
            onSuccess = {
                pendingInviteCode.value = ""
                Log.i(TAG, "CLAIM_REFERRAL completed via Action Queue for code $cleanCode")
                Result.success(Unit)
            },
            onFailure = { e ->
                val message = when {
                    e.message?.contains("Cannot use your own", ignoreCase = true) == true -> "Cannot use your own code"
                    e.message?.contains("Bonus Already Claimed", ignoreCase = true) == true ||
                    e.message?.contains("ALREADY_CLAIMED", ignoreCase = true) == true -> "Bonus Already Claimed"
                    e.message?.contains("Invalid", ignoreCase = true) == true ||
                    e.message?.contains("REFERRER_NOT_FOUND", ignoreCase = true) == true ||
                    e.message?.contains("not found", ignoreCase = true) == true -> "Invalid Code"
                    else -> e.message ?: "Failed to process referral code. Please check your connection."
                }
                Log.w(TAG, "CLAIM_REFERRAL failed: $message")
                Result.failure(Exception(message, e))
            }
        )
    }

    /**
     * Overload for claimReferralCode without requiring explicit context.
     */
    suspend fun claimReferralCode(submittedCode: String): Result<Unit> {
        val appCtx = LicenseManager.getAppContext() ?: try {
            FirebaseAuth.getInstance().app.applicationContext
        } catch (_: Exception) {
            null
        } ?: return Result.failure(IllegalStateException("Application context is unavailable."))
        return claimReferralCode(appCtx, submittedCode)
    }
}
