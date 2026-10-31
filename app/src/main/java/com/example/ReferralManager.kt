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
     * Executes the atomic referral claim transaction directly using Firestore SDK.
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

        try {
            val db = FirebaseFirestore.getInstance()
            val querySnapshot = db.collection("users_licensing")
                .whereEqualTo("referralCode", cleanCode)
                .limit(1)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                return@withContext Result.failure(IllegalArgumentException("Invalid Code"))
            }

            val referrerDocSnap = querySnapshot.documents[0]
            val referrerId = referrerDocSnap.id
            val referrerUserId = referrerDocSnap.getString("userId") ?: referrerId
            val referrerUserEmail = referrerDocSnap.getString("userEmail") ?: ""

            if (referrerId == currentUser.uid ||
                referrerUserId == currentUser.uid ||
                (!currentUser.email.isNullOrBlank() && referrerUserEmail.equals(currentUser.email, ignoreCase = true))
            ) {
                return@withContext Result.failure(IllegalArgumentException("Cannot use your own code"))
            }

            val currentUserDocRef = db.collection("users_licensing").document(currentUser.uid)
            val referrerDocRef = db.collection("users_licensing").document(referrerId)

            val newPoints = db.runTransaction { transaction ->
                val userSnap = transaction.get(currentUserDocRef)
                if (userSnap.exists() && userSnap.getBoolean("hasClaimedReferral") == true) {
                    throw IllegalStateException("Bonus Already Claimed")
                }

                val currentPoints = (userSnap.getLong("pointsBalance") ?: 0L).toInt()
                val updatedPoints = currentPoints + WELCOME_BONUS_POINTS

                val referrerSnap = transaction.get(referrerDocRef)
                val referrerPoints = (referrerSnap.getLong("pointsBalance") ?: 0L).toInt() + REFERRAL_BONUS_POINTS
                val referrerCount = (referrerSnap.getLong("referralCount") ?: 0L).toInt() + 1

                // Update current user doc
                val userUpdates = mutableMapOf<String, Any>(
                    "hasClaimedReferral" to true,
                    "claimedReferralCode" to cleanCode,
                    "referredBy" to referrerId,
                    "pointsBalance" to updatedPoints,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (userSnap.exists()) {
                    transaction.update(currentUserDocRef, userUpdates)
                } else {
                    userUpdates["userId"] = currentUser.uid
                    userUpdates["userEmail"] = currentUser.email ?: ""
                    userUpdates["referralCode"] = LicenseManager.generateReferralCode()
                    userUpdates["referralCount"] = 0
                    userUpdates["activePassType"] = ""
                    userUpdates["passExpiryTimestamp"] = 0L
                    userUpdates["unlocked_features"] = emptyMap<String, Long>()
                    userUpdates["createdAt"] = FieldValue.serverTimestamp()
                    transaction.set(currentUserDocRef, userUpdates)
                }

                // Update referrer doc
                transaction.update(
                    referrerDocRef,
                    mapOf(
                        "pointsBalance" to referrerPoints,
                        "referralCount" to referrerCount,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                // Add referral audit record
                val auditRef = db.collection("users")
                    .document(referrerId)
                    .collection("referrals")
                    .document(currentUser.uid)
                transaction.set(
                    auditRef,
                    mapOf(
                        "refereeId" to currentUser.uid,
                        "refereeEmail" to (currentUser.email ?: ""),
                        "claimedAt" to FieldValue.serverTimestamp()
                    )
                )

                updatedPoints
            }.await()

            // Update in-memory reactive state on success
            LicenseManager.notifyReferralClaimed(newPoints)
            pendingInviteCode.value = ""
            Log.i(TAG, "Referral claimed directly via Firestore SDK: referee +50 pts, referrer +20 pts")
            Result.success(Unit)
        } catch (e: Exception) {
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
