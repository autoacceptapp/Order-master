package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ReferralManager
 *
 * Implements a secure, atomic Firestore ACID transaction for Order Master's Refer & Earn system:
 * 1. Validate input (length 8, not empty).
 * 2. Block Self-Referral: Return failure if submittedCode equals LicenseManager.referralCode.value.
 * 3. Pre-Transaction Query: Query Firestore (users_licensing) using whereEqualTo("referralCode", submittedCode)
 *    to find the referrer's document ID BEFORE starting runTransaction. If empty, return "Invalid Code".
 * 4. Secure Atomic Transaction (db.runTransaction):
 *    - Read current user document (users_licensing/{currentUserUid}).
 *    - Strictly check hasClaimedReferral == false inside transaction to prevent double-claim race conditions.
 *    - Update referrer document: pointsBalance + 20, referralCount + 1.
 *    - Update current user document: pointsBalance + 50, hasClaimedReferral = true, claimedFromCode = submittedCode.
 * 5. Catch exceptions and return clean Result.failure strings.
 */
object ReferralManager {

    private const val TAG = "ReferralManager"
    private const val COLL_USERS_LICENSING = "users_licensing"

    const val REFERRAL_BONUS_POINTS = 20
    const val WELCOME_BONUS_POINTS = 50

    // Deep link captured invite code
    val pendingInviteCode = MutableStateFlow<String>("")

    /**
     * Executes the atomic referral claim transaction.
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

        val currentUid = currentUser.uid

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

            // 3. Pre-Transaction Query: find referrer document ID before starting transaction
            val querySnapshot = db.collection(COLL_USERS_LICENSING)
                .whereEqualTo("referralCode", cleanCode)
                .limit(1)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                return@withContext Result.failure(IllegalArgumentException("Invalid Code"))
            }

            val referrerDocSnapshot = querySnapshot.documents.first()
            val referrerDocRef = referrerDocSnapshot.reference
            val referrerUid = referrerDocSnapshot.getString("userId") ?: referrerDocSnapshot.id

            // Block self-referral if document matches current user
            if (referrerUid == currentUid || referrerDocSnapshot.id == currentUid) {
                return@withContext Result.failure(IllegalArgumentException("Cannot use your own code"))
            }

            val currentUserDocRef = db.collection(COLL_USERS_LICENSING).document(currentUid)
            var newCurrentPoints = 0

            // 4. Secure Atomic Transaction
            db.runTransaction { transaction ->
                // Read current user document
                val freshCurrentDoc = transaction.get(currentUserDocRef)

                // Read referrer document
                val freshReferrerDoc = transaction.get(referrerDocRef)
                if (!freshReferrerDoc.exists()) {
                    throw IllegalArgumentException("Invalid Code")
                }

                if (freshReferrerDoc.id == currentUid) {
                    throw IllegalArgumentException("Cannot use your own code")
                }

                // Strictly check hasClaimedReferral == false inside transaction
                val hasClaimedOnServer = freshCurrentDoc.getBoolean("hasClaimedReferral") ?: false
                if (hasClaimedOnServer) {
                    throw IllegalStateException("Bonus Already Claimed")
                }

                val currentPoints = freshCurrentDoc.getLong("pointsBalance") ?: 0L
                val referrerPoints = freshReferrerDoc.getLong("pointsBalance") ?: 0L
                val referrerCount = freshReferrerDoc.getLong("referralCount") ?: 0L

                val resultingCurrentPoints = currentPoints + WELCOME_BONUS_POINTS
                val resultingReferrerPoints = referrerPoints + REFERRAL_BONUS_POINTS
                newCurrentPoints = resultingCurrentPoints.toInt()

                // Update referrer document: pointsBalance + 20, referralCount + 1
                transaction.update(
                    referrerDocRef,
                    mapOf(
                        "pointsBalance" to resultingReferrerPoints,
                        "referralCount" to (referrerCount + 1L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                // Update current user document: pointsBalance + 50, hasClaimedReferral = true, claimedFromCode = submittedCode
                transaction.set(
                    currentUserDocRef,
                    mapOf(
                        "userId" to currentUid,
                        "pointsBalance" to resultingCurrentPoints,
                        "hasClaimedReferral" to true,
                        "claimedFromCode" to cleanCode,
                        "claimedFrom" to cleanCode,
                        "claimedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }.await()

            // Update in-memory reactive state on success
            LicenseManager.notifyReferralClaimed(newCurrentPoints)
            pendingInviteCode.value = ""
            Log.i(TAG, "Referral claimed: referee +50 pts, referrer +20 pts")
            Result.success(Unit)
        } catch (e: Exception) {
            val message = when {
                e is IllegalArgumentException -> e.message ?: "Invalid Code"
                e is IllegalStateException -> e.message ?: "Bonus Already Claimed"
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
