package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
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
 * Implements Refer & Earn system via atomic Firebase Firestore Transaction:
 * 1. Validate input (length 8, not empty).
 * 2. Block Self-Referral: Return failure if submittedCode equals local or stored referral code.
 * 3. Atomic Firestore Transaction:
 *    - ALL get() reads occur sequentially BEFORE any writes.
 *      1. val userSnap = transaction.get(currentUserDocRef)
 *      2. val referrerSnap = transaction.get(referrerDocRef)
 *    - Validates user & referrer existence and un-claimed status.
 *    - Calculates new points and updates both referee (+50 pts) and referrer (+20 pts).
 *    - Sets audit log entry under referrer's referrals subcollection.
 */
object ReferralManager {

    private const val TAG = "ReferralManager"
    private const val COLLECTION_USERS = "users"
    private const val SUBCOLLECTION_REFERRALS = "referrals"

    const val REFERRAL_BONUS_POINTS = 20
    const val WELCOME_BONUS_POINTS = 50

    // Deep link captured invite code
    val pendingInviteCode = MutableStateFlow<String>("")

    /**
     * Executes the referral claim via an atomic Firestore Transaction with strict read-before-write ordering.
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

        if (FirebaseApp.getApps(context).isEmpty()) {
            try {
                FirebaseApp.initializeApp(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed initializing FirebaseApp: ${e.message}")
            }
        }

        val auth = try {
            if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseAuth.getInstance() else null
        } catch (_: Exception) {
            null
        }

        val currentUser = auth?.currentUser
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

        val db = FirebaseFirestore.getInstance()

        try {
            // Find the referrer's account by referralCode
            val querySnapshot = db.collection(COLLECTION_USERS)
                .whereEqualTo("referralCode", cleanCode)
                .limit(1)
                .get()
                .await()

            val referrerDoc = if (!querySnapshot.isEmpty) {
                querySnapshot.documents[0]
            } else {
                val fallbackQuery = db.collection(COLLECTION_USERS)
                    .whereEqualTo("referral_code", cleanCode)
                    .limit(1)
                    .get()
                    .await()
                if (!fallbackQuery.isEmpty) fallbackQuery.documents[0] else null
            }

            if (referrerDoc == null) {
                return@withContext Result.failure(IllegalArgumentException("Invalid Code"))
            }

            val currentUserKey = LicenseManager.resolveUserKey(context)
            val currentUserDocRef = db.collection(COLLECTION_USERS).document(currentUserKey)
            val referrerDocRef = db.collection(COLLECTION_USERS).document(referrerDoc.id)

            // Anti-fraud: cannot refer own document ID
            if (currentUserKey == referrerDoc.id) {
                return@withContext Result.failure(IllegalArgumentException("Cannot use your own code"))
            }

            // 3. Atomic Firestore Transaction with strict read-before-write ordering
            val finalNewPoints = db.runTransaction { transaction ->
                // READ 1: Current User Document
                val userSnap = transaction.get(currentUserDocRef)

                // READ 2: Referrer Document
                val referrerSnap = transaction.get(referrerDocRef)

                // --- VALIDATIONS (after all reads) ---
                if (!userSnap.exists()) {
                    throw IllegalStateException("User document does not exist.")
                }

                if (!referrerSnap.exists()) {
                    throw IllegalArgumentException("Invalid Code")
                }

                // Prevent self-referral
                if (userSnap.id == referrerSnap.id) {
                    throw IllegalArgumentException("Cannot use your own code")
                }

                val userReferralCode = userSnap.getString("referralCode") ?: userSnap.getString("referral_code")
                if (!userReferralCode.isNullOrBlank() && userReferralCode.equals(cleanCode, ignoreCase = true)) {
                    throw IllegalArgumentException("Cannot use your own code")
                }

                val hasClaimed = userSnap.getBoolean("hasClaimedReferral")
                    ?: userSnap.getBoolean("has_used_referral")
                    ?: false
                if (hasClaimed) {
                    throw IllegalStateException("Bonus Already Claimed")
                }

                // --- CALCULATE NEW POINTS ---
                val currentUserPoints = userSnap.getLong("pointsBalance")?.toInt()
                    ?: userSnap.getLong("points")?.toInt()
                    ?: LicenseManager.pointsBalance.value
                val newUserPoints = currentUserPoints + WELCOME_BONUS_POINTS

                val referrerPoints = referrerSnap.getLong("pointsBalance")?.toInt()
                    ?: referrerSnap.getLong("points")?.toInt()
                    ?: 0
                val newReferrerPoints = referrerPoints + REFERRAL_BONUS_POINTS

                val referrerCount = referrerSnap.getLong("referralCount")?.toInt()
                    ?: referrerSnap.getLong("referral_count")?.toInt()
                    ?: 0
                val newReferrerCount = referrerCount + 1

                // --- EXECUTE WRITES (strictly after all reads) ---
                // A. Update current user
                val userUpdates = mapOf<String, Any>(
                    "hasClaimedReferral" to true,
                    "has_used_referral" to true,
                    "referredBy" to cleanCode,
                    "referredByDocId" to referrerSnap.id,
                    "pointsBalance" to newUserPoints,
                    "points" to newUserPoints,
                    "referralClaimedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                transaction.update(currentUserDocRef, userUpdates)

                // B. Update referrer
                val referrerUpdates = mapOf<String, Any>(
                    "pointsBalance" to newReferrerPoints,
                    "points" to newReferrerPoints,
                    "referralCount" to newReferrerCount,
                    "referral_count" to newReferrerCount,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                transaction.update(referrerDocRef, referrerUpdates)

                // C. Set audit log in referrer's subcollection
                val auditLogDocRef = referrerDocRef.collection(SUBCOLLECTION_REFERRALS).document(userSnap.id)
                val auditData = mapOf<String, Any>(
                    "refereeId" to userSnap.id,
                    "refereeUid" to (userSnap.getString("firebaseUid") ?: currentUser.uid),
                    "referralCodeUsed" to cleanCode,
                    "bonusAwarded" to REFERRAL_BONUS_POINTS,
                    "timestamp" to FieldValue.serverTimestamp()
                )
                transaction.set(auditLogDocRef, auditData)

                newUserPoints
            }.await()

            LicenseManager.notifyReferralClaimed(finalNewPoints)
            pendingInviteCode.value = ""
            Log.i(TAG, "Referral transaction completed successfully. Awarded $WELCOME_BONUS_POINTS points to user and $REFERRAL_BONUS_POINTS points to referrer.")
            Result.success(Unit)

        } catch (e: Exception) {
            val message = when {
                e.message?.contains("Cannot use your own", ignoreCase = true) == true -> "Cannot use your own code"
                e.message?.contains("Bonus Already Claimed", ignoreCase = true) == true ||
                e.message?.contains("ALREADY_CLAIMED", ignoreCase = true) == true -> "Bonus Already Claimed"
                e.message?.contains("Invalid referral code", ignoreCase = true) == true ||
                e.message?.contains("Invalid Code", ignoreCase = true) == true ||
                e.message?.contains("REFERRER_NOT_FOUND", ignoreCase = true) == true ||
                e.message?.contains("not found", ignoreCase = true) == true -> "Invalid Code"
                e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ->
                    "Server security policy prevented referral claim. Please try again."
                else -> e.message ?: "Failed to process referral code. Please check your connection."
            }
            Log.e(TAG, "Referral claim failed: $message", e)
            Result.failure(Exception(message, e))
        }
    }

    /**
     * Overload for claimReferralCode without requiring explicit context.
     */
    suspend fun claimReferralCode(submittedCode: String): Result<Unit> {
        val appCtx = LicenseManager.getAppContext()
        val dummyCtx = appCtx ?: try {
            FirebaseAuth.getInstance().app.applicationContext
        } catch (_: Exception) {
            null
        }
        if (dummyCtx == null) {
            return Result.failure(IllegalStateException("Application context is unavailable."))
        }
        return claimReferralCode(dummyCtx, submittedCode)
    }
}
