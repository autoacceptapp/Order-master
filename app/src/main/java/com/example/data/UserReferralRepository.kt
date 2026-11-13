package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.HardwareIdManager
import com.example.LicenseManager
import com.example.ReferralManager
import com.example.auth.GoogleAuthManager
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * User Profile and Referral Data Model
 */
data class UserProfile(
    val hardwareId: String = "",
    val referralCode: String = "",
    val points: Long = 0L,
    val hasUsedReferral: Boolean = false,
    val referredBy: String? = null,
    val referralCount: Long = 0L,
    val welcomeBonusReceived: Boolean = false,
    val isFirestoreConnected: Boolean = true
)

/**
 * Result data class for referral code submission
 */
sealed class ReferralSubmissionResult {
    data class Success(val pointsEarned: Long, val message: String) : ReferralSubmissionResult()
    data class Error(val message: String) : ReferralSubmissionResult()
}

/**
 * UserReferralRepository
 *
 * Zero-Trust Thin-Client facade over LicenseManager & ReferralManager:
 * - Zero direct Firestore writes to `users/{hardwareId}` or unauthenticated profiles.
 * - Zero local SharedPreferences fabrication of points balances.
 * - All referral submissions route strictly through `ReferralManager` -> `pending_actions` (`CLAIM_REFERRAL`).
 */
object UserReferralRepository {

    private val _currentUserProfile = MutableStateFlow<UserProfile?>(null)
    val currentUserProfile: StateFlow<UserProfile?> = _currentUserProfile.asStateFlow()

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

    fun generateReferralCode(hardwareId: String): String {
        val clean = hardwareId.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        val seed = if (clean.length >= 8) clean.takeLast(6) else clean.padEnd(6, 'X')
        return "OM$seed"
    }

    suspend fun getOrCreateUserProfile(context: Context): UserProfile = withContext(Dispatchers.IO) {
        val profile = readLocalProfile(context, HardwareIdManager.getPermanentHardwareId(context))
        _currentUserProfile.value = profile
        profile
    }

    suspend fun submitReferralCode(
        context: Context,
        inputCode: String
    ): ReferralSubmissionResult = withContext(Dispatchers.IO) {
        val result = ReferralManager.claimReferralCode(context, inputCode)
        result.fold(
            onSuccess = {
                val updated = readLocalProfile(context, HardwareIdManager.getPermanentHardwareId(context))
                _currentUserProfile.value = updated
                ReferralSubmissionResult.Success(
                    pointsEarned = ReferralManager.WELCOME_BONUS_POINTS.toLong(),
                    message = "Referral code applied successfully!"
                )
            },
            onFailure = { err ->
                ReferralSubmissionResult.Error(
                    err.message ?: "Failed to submit referral code."
                )
            }
        )
    }

    fun observeUserProfile(context: Context): Flow<UserProfile> {
        val hardwareId = HardwareIdManager.getPermanentHardwareId(context)
        return combine(
            LicenseManager.referralCode,
            LicenseManager.pointsBalance,
            LicenseManager.hasClaimedReferral,
            LicenseManager.referralCount,
            GoogleAuthManager.userAuthState
        ) { code, points, claimed, count, auth ->
            val profile = UserProfile(
                hardwareId = hardwareId,
                referralCode = code.orEmpty(),
                points = points.toLong(),
                hasUsedReferral = claimed,
                referredBy = null,
                referralCount = count.toLong(),
                welcomeBonusReceived = auth.isLoggedIn && points > 0,
                isFirestoreConnected = isNetworkAvailable(context) && auth.isLoggedIn
            )
            _currentUserProfile.value = profile
            profile
        }
    }

    fun readLocalProfile(context: Context, hardwareId: String): UserProfile {
        val points = LicenseManager.pointsBalance.value.toLong()
        return UserProfile(
            hardwareId = hardwareId,
            referralCode = LicenseManager.referralCode.value.orEmpty(),
            points = points,
            hasUsedReferral = LicenseManager.hasClaimedReferral.value,
            referredBy = null,
            referralCount = LicenseManager.referralCount.value.toLong(),
            welcomeBonusReceived = points > 0L,
            isFirestoreConnected = isNetworkAvailable(context)
        )
    }
}

/**
 * Lightweight coroutine await extension for Google Play Services Tasks.
 */
internal suspend fun <T> Task<T>.await(): T {
    if (isComplete) {
        val e = exception
        return if (e == null) {
            if (isCanceled) {
                throw java.util.concurrent.CancellationException("Task $this was cancelled.")
            } else {
                result
            }
        } else {
            throw e
        }
    }

    return suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val e = task.exception
            if (e == null) {
                if (task.isCanceled) {
                    cont.cancel()
                } else {
                    cont.resume(task.result)
                }
            } else {
                cont.resumeWith(Result.failure(e))
            }
        }
    }
}
