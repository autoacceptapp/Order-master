package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.HardwareIdManager
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
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
    val points: Long = 50L,
    val hasUsedReferral: Boolean = false,
    val referredBy: String? = null,
    val referralCount: Long = 0L,
    val welcomeBonusReceived: Boolean = true,
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
 * Manages all Firebase Firestore database operations for the "Refer & Earn" system:
 * - Anti-fraud identity tied to permanent DRM Hardware ID (MediaDrm Widevine UUID).
 * - Automatic 50 Welcome Points credit on first profile creation.
 * - Atomic referral code submission with 20 points reward to the referrer.
 * - Protection against self-referrals, double-claiming, and factory reset abuse.
 * - Seamless offline-first persistence with SharedPreferences and Firestore local cache.
 */
object UserReferralRepository {

    private const val TAG = "UserReferralRepository"
    private const val COLLECTION_USERS = "users"
    private const val SUBCOLLECTION_REFERRALS = "referrals"
    private const val PREFS_NAME = "referral_user_prefs"

    // SharedPreferences Keys for offline caching
    private const val KEY_CACHED_POINTS = "cached_points"
    private const val KEY_CACHED_CODE = "cached_referral_code"
    private const val KEY_CACHED_HAS_USED = "cached_has_used_referral"
    private const val KEY_CACHED_REFERRED_BY = "cached_referred_by"
    private const val KEY_CACHED_REFERRAL_COUNT = "cached_referral_count"

    @Volatile
    private var isFirebaseInitialized = false

    private val _currentUserProfile = MutableStateFlow<UserProfile?>(null)
    val currentUserProfile: StateFlow<UserProfile?> = _currentUserProfile.asStateFlow()

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
     * Initializes or retrieves the FirebaseFirestore instance safely.
     * Configures offline persistent cache settings.
     */
    private fun getFirestore(context: Context): FirebaseFirestore? {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context.applicationContext)
                Log.i(TAG, "Firebase initialized.")
            }
            isFirebaseInitialized = true
            val firestore = FirebaseFirestore.getInstance()
            try {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                    .build()
                firestore.firestoreSettings = settings
            } catch (_: Exception) {
                // Ignore if settings have already been initialized
            }
            firestore
        } catch (e: Exception) {
            Log.i(TAG, "Firebase initialization notice: ${e.message}")
            null
        }
    }

    /**
     * Generates a clean, short, memorable referral code from device Hardware ID.
     * E.g. "OM8F92A1"
     */
    fun generateReferralCode(hardwareId: String): String {
        val clean = hardwareId.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        val seed = if (clean.length >= 8) {
            clean.takeLast(6)
        } else {
            clean.padEnd(6, 'X')
        }
        return "OM$seed"
    }

    /**
     * Retrieves or creates the user's permanent profile in Firestore.
     * Uses the physical DRM Hardware ID as the immutable Firestore document ID.
     *
     * Rules:
     * - Immediate offline-first return with 50 welcome points.
     * - If document doesn't exist on Firestore: creates document with 50 welcome points.
     * - If document exists: loads existing profile (persisting across factory resets).
     * - Gracefully falls back to offline state without error if network is unavailable.
     */
    suspend fun getOrCreateUserProfile(context: Context): UserProfile = withContext(Dispatchers.IO) {
        val hardwareId = HardwareIdManager.getPermanentHardwareId(context)
        val localCached = readLocalProfile(context, hardwareId)
        _currentUserProfile.value = localCached

        if (!isNetworkAvailable(context)) {
            Log.i(TAG, "Offline mode active: Using permanent hardware profile ($hardwareId)")
            return@withContext localCached
        }

        val firestore = getFirestore(context)
        if (firestore == null) {
            Log.i(TAG, "Firestore unavailable, operating in local offline mode.")
            return@withContext localCached
        }

        try {
            val docRef = firestore.collection(COLLECTION_USERS).document(hardwareId)
            val snapshot = docRef.get().await()

            if (snapshot.exists()) {
                val points = snapshot.getLong("points") ?: 50L
                val code = snapshot.getString("referral_code") ?: generateReferralCode(hardwareId)
                val hasUsed = snapshot.getBoolean("has_used_referral") ?: false
                val referredBy = snapshot.getString("referred_by")
                val refCount = snapshot.getLong("referral_count") ?: 0L
                val welcomeReceived = snapshot.getBoolean("welcome_bonus_received") ?: true

                val profile = UserProfile(
                    hardwareId = hardwareId,
                    referralCode = code,
                    points = points,
                    hasUsedReferral = hasUsed,
                    referredBy = referredBy,
                    referralCount = refCount,
                    welcomeBonusReceived = welcomeReceived,
                    isFirestoreConnected = true
                )

                saveLocalProfile(context, profile)
                _currentUserProfile.value = profile
                return@withContext profile
            } else {
                // First-time launch on this physical device: Create new profile with 50 Welcome Points
                val newCode = generateReferralCode(hardwareId)
                val newUserData = hashMapOf(
                    "hardware_id" to hardwareId,
                    "referral_code" to newCode,
                    "points" to 50L, // 50 Welcome Points credited
                    "has_used_referral" to false,
                    "referred_by" to null,
                    "referral_count" to 0L,
                    "welcome_bonus_received" to true,
                    "created_at" to FieldValue.serverTimestamp(),
                    "updated_at" to FieldValue.serverTimestamp()
                )

                docRef.set(newUserData, SetOptions.merge()).await()
                Log.i(TAG, "Created new user profile in Firestore for Hardware ID: $hardwareId with 50 welcome points.")

                val profile = UserProfile(
                    hardwareId = hardwareId,
                    referralCode = newCode,
                    points = 50L,
                    hasUsedReferral = false,
                    referredBy = null,
                    referralCount = 0L,
                    welcomeBonusReceived = true,
                    isFirestoreConnected = true
                )

                saveLocalProfile(context, profile)
                _currentUserProfile.value = profile
                return@withContext profile
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                e.message?.contains("offline", ignoreCase = true) == true) {
                Log.i(TAG, "Firestore is in offline mode: using local device profile.")
            } else {
                Log.w(TAG, "Notice while checking Firestore profile: ${e.message}")
            }
            val profile = localCached.copy(isFirestoreConnected = false)
            _currentUserProfile.value = profile
            return@withContext profile
        } catch (e: Exception) {
            Log.i(TAG, "Notice while syncing profile: ${e.message}")
            val profile = localCached.copy(isFirestoreConnected = false)
            _currentUserProfile.value = profile
            return@withContext profile
        }
    }

    /**
     * Submits a referral code entered by the user.
     *
     * Rules enforced:
     * 1. Requires active internet connection.
     * 2. Cannot enter own referral code.
     * 3. Cannot submit if has_used_referral is already true.
     * 4. Validates referral code exists in Firestore.
     * 5. Cannot refer own device (same Hardware ID).
     * 6. Atomically increments referrer's points by 20 and referral_count by 1.
     * 7. Atomically marks current user's document as has_used_referral = true with referred_by recorded.
     */
    suspend fun submitReferralCode(
        context: Context,
        inputCode: String
    ): ReferralSubmissionResult = withContext(Dispatchers.IO) {
        val cleanCode = inputCode.trim().uppercase(Locale.ROOT)
        val hardwareId = HardwareIdManager.getPermanentHardwareId(context)

        if (cleanCode.isBlank()) {
            return@withContext ReferralSubmissionResult.Error("Please enter a referral code.")
        }

        // 1. Check current profile state
        val currentProfile = _currentUserProfile.value ?: getOrCreateUserProfile(context)

        if (currentProfile.hasUsedReferral) {
            return@withContext ReferralSubmissionResult.Error("You have already claimed a referral bonus on this device.")
        }

        if (cleanCode.equals(currentProfile.referralCode, ignoreCase = true)) {
            return@withContext ReferralSubmissionResult.Error("You cannot use your own referral code.")
        }

        if (!isNetworkAvailable(context)) {
            return@withContext ReferralSubmissionResult.Error("No internet connection detected. Please connect to the internet to claim a referral code.")
        }

        val firestore = getFirestore(context)
            ?: return@withContext ReferralSubmissionResult.Error("Firestore database service is currently connecting. Please check your internet connection.")

        try {
            // 2. Query Firestore to find the referrer's account by referral_code
            val querySnapshot = firestore.collection(COLLECTION_USERS)
                .whereEqualTo("referral_code", cleanCode)
                .limit(1)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                return@withContext ReferralSubmissionResult.Error("Invalid referral code. Please check the code and try again.")
            }

            val referrerDoc = querySnapshot.documents[0]
            val referrerHardwareId = referrerDoc.id

            // Anti-fraud: Cannot refer own hardware ID even if code matches
            if (referrerHardwareId == hardwareId) {
                return@withContext ReferralSubmissionResult.Error("You cannot refer your own device.")
            }

            val currentUserDocRef = firestore.collection(COLLECTION_USERS).document(hardwareId)
            val referrerDocRef = firestore.collection(COLLECTION_USERS).document(referrerHardwareId)

            // 3. Atomic Firestore Transaction: Ensure consistency and prevent race conditions
            firestore.runTransaction { transaction ->
                val freshCurrentDoc = transaction.get(currentUserDocRef)
                val alreadyUsed = freshCurrentDoc.getBoolean("has_used_referral") ?: false
                if (alreadyUsed) {
                    throw IllegalStateException("ALREADY_CLAIMED")
                }

                val freshReferrerDoc = transaction.get(referrerDocRef)
                if (!freshReferrerDoc.exists()) {
                    throw IllegalStateException("REFERRER_NOT_FOUND")
                }

                // A. Atomically increment referrer's points by 20 and referral_count by 1
                transaction.update(
                    referrerDocRef,
                    mapOf(
                        "points" to FieldValue.increment(20),
                        "referral_count" to FieldValue.increment(1),
                        "updated_at" to FieldValue.serverTimestamp()
                    )
                )

                // B. Update current user: set has_used_referral = true, record referred_by
                transaction.update(
                    currentUserDocRef,
                    mapOf(
                        "has_used_referral" to true,
                        "referred_by" to cleanCode,
                        "referred_by_id" to referrerHardwareId,
                        "referral_claimed_at" to FieldValue.serverTimestamp(),
                        "updated_at" to FieldValue.serverTimestamp()
                    )
                )
                null
            }.await()

            // 4. Record audit event in referrer's subcollection for fraud-free tracking
            try {
                val auditData = hashMapOf(
                    "referee_hardware_id" to hardwareId,
                    "referral_code_used" to cleanCode,
                    "reward_points" to 20L,
                    "timestamp" to FieldValue.serverTimestamp()
                )
                referrerDocRef.collection(SUBCOLLECTION_REFERRALS).document(hardwareId).set(auditData).await()
            } catch (auditEx: Exception) {
                Log.w(TAG, "Notice: could not write audit log entry: ${auditEx.message}")
            }

            // 5. Update local state and cache
            val updatedProfile = currentProfile.copy(
                hasUsedReferral = true,
                referredBy = cleanCode
            )
            saveLocalProfile(context, updatedProfile)
            _currentUserProfile.value = updatedProfile

            Log.i(TAG, "Referral successfully applied: Code $cleanCode from referrer $referrerHardwareId")
            return@withContext ReferralSubmissionResult.Success(
                pointsEarned = 20L,
                message = "Referral code applied successfully! 20 points awarded to your referrer."
            )
        } catch (e: Exception) {
            val errorMsg = when {
                e.message?.contains("ALREADY_CLAIMED") == true ->
                    "A referral code has already been claimed on this device."
                e.message?.contains("REFERRER_NOT_FOUND") == true ->
                    "Referrer account was not found. Please try another code."
                e.message?.contains("offline", ignoreCase = true) == true ->
                    "Database is currently offline. Please check your internet connection."
                else ->
                    "Failed to submit referral code: ${e.localizedMessage ?: "Unknown error"}"
            }
            Log.w(TAG, "Referral submission notice: $errorMsg")
            return@withContext ReferralSubmissionResult.Error(errorMsg)
        }
    }

    /**
     * Observes real-time updates for the current user's profile from Firestore.
     * Ensures points and referral stats update immediately when someone uses their code.
     */
    fun observeUserProfile(context: Context): Flow<UserProfile> = callbackFlow {
        val hardwareId = HardwareIdManager.getPermanentHardwareId(context)
        val local = readLocalProfile(context, hardwareId)
        trySend(local)

        val firestore = getFirestore(context)
        if (firestore == null) {
            awaitClose { }
            return@callbackFlow
        }

        val docRef = firestore.collection(COLLECTION_USERS).document(hardwareId)
        var registration: ListenerRegistration? = null

        try {
            registration = docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                        error.message?.contains("offline", ignoreCase = true) == true) {
                        Log.i(TAG, "SnapshotListener waiting for online sync.")
                    } else {
                        Log.w(TAG, "SnapshotListener notice: ${error.message}")
                    }
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val points = snapshot.getLong("points") ?: 50L
                    val code = snapshot.getString("referral_code") ?: generateReferralCode(hardwareId)
                    val hasUsed = snapshot.getBoolean("has_used_referral") ?: false
                    val referredBy = snapshot.getString("referred_by")
                    val refCount = snapshot.getLong("referral_count") ?: 0L
                    val welcomeReceived = snapshot.getBoolean("welcome_bonus_received") ?: true

                    val profile = UserProfile(
                        hardwareId = hardwareId,
                        referralCode = code,
                        points = points,
                        hasUsedReferral = hasUsed,
                        referredBy = referredBy,
                        referralCount = refCount,
                        welcomeBonusReceived = welcomeReceived,
                        isFirestoreConnected = true
                    )

                    saveLocalProfile(context, profile)
                    _currentUserProfile.value = profile
                    trySend(profile)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Could not attach Firestore snapshot listener: ${e.message}")
        }

        awaitClose {
            registration?.remove()
        }
    }

    /**
     * Saves user profile to local SharedPreferences for instant offline display.
     */
    private fun saveLocalProfile(context: Context, profile: UserProfile) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_CACHED_POINTS, profile.points)
            .putString(KEY_CACHED_CODE, profile.referralCode)
            .putBoolean(KEY_CACHED_HAS_USED, profile.hasUsedReferral)
            .putString(KEY_CACHED_REFERRED_BY, profile.referredBy)
            .putLong(KEY_CACHED_REFERRAL_COUNT, profile.referralCount)
            .apply()
    }

    /**
     * Reads cached profile from SharedPreferences or creates initial default state.
     */
    fun readLocalProfile(context: Context, hardwareId: String): UserProfile {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultCode = generateReferralCode(hardwareId)

        if (!prefs.contains(KEY_CACHED_POINTS)) {
            prefs.edit()
                .putLong(KEY_CACHED_POINTS, 50L)
                .putString(KEY_CACHED_CODE, defaultCode)
                .putBoolean(KEY_CACHED_HAS_USED, false)
                .putLong(KEY_CACHED_REFERRAL_COUNT, 0L)
                .apply()
        }

        return UserProfile(
            hardwareId = hardwareId,
            referralCode = prefs.getString(KEY_CACHED_CODE, defaultCode) ?: defaultCode,
            points = prefs.getLong(KEY_CACHED_POINTS, 50L),
            hasUsedReferral = prefs.getBoolean(KEY_CACHED_HAS_USED, false),
            referredBy = prefs.getString(KEY_CACHED_REFERRED_BY, null),
            referralCount = prefs.getLong(KEY_CACHED_REFERRAL_COUNT, 0L),
            welcomeBonusReceived = true,
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
