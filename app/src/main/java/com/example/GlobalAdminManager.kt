package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Representation of a user's ban status.
 */
sealed class UserBanState {
    data object NotBanned : UserBanState()
    data class Banned(
        val banUntilTimestamp: Long,
        val formattedDateTime: String,
        val reason: String? = null
    ) : UserBanState()
}

/**
 * GlobalAdminManager
 *
 * Enforces real-time global administrative control:
 * 1. Global Service Switch (`app_settings/global`): Kill switch controlled by Admin.
 *    If `isServiceActive` is false, immediately activates a full-screen, non-dismissible blocking UI
 *    and suppresses all automated background/accessibility actions.
 * 2. User Ban System (`users/{uid}`): Time-based ban enforcement based on `banUntil` Timestamp.
 *    If `banUntil > current time`, activates a non-dismissible blocking UI showing ban expiration.
 */
object GlobalAdminManager {

    private const val TAG = "GlobalAdminManager"
    private const val COLL_APP_SETTINGS = "app_settings"
    private const val DOC_GLOBAL = "global"
    private const val COLL_USERS = "users"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    private val isInitialized = AtomicBoolean(false)

    // Listener registrations
    private var globalSettingsListener: ListenerRegistration? = null
    private var userDocListener: ListenerRegistration? = null

    // State: Global Kill Switch
    private val _isServiceActive = MutableStateFlow(true)
    val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

    private val _offlineMessage = MutableStateFlow("Service is currently offline for scheduled maintenance. Please check back later.")
    val offlineMessage: StateFlow<String> = _offlineMessage.asStateFlow()

    // State: Payment System Kill Switch
    private val _isPaymentSystemActive = MutableStateFlow(true)
    val isPaymentSystemActive: StateFlow<Boolean> = _isPaymentSystemActive.asStateFlow()

    // State: In-App GitHub Update Kill Switch
    private val _isInAppUpdateActive = MutableStateFlow(true)
    val isInAppUpdateActive: StateFlow<Boolean> = _isInAppUpdateActive.asStateFlow()

    // State: User Ban
    private val _banState = MutableStateFlow<UserBanState>(UserBanState.NotBanned)
    val banState: StateFlow<UserBanState> = _banState.asStateFlow()

    // Combined Blocked State: either service is offline or user is banned
    private val _isBlocked = MutableStateFlow(false)
    val isBlocked: StateFlow<Boolean> = _isBlocked.asStateFlow()

    private var trackedUid: String? = null

    // Periodic ticker to automatically unban once banUntil timestamp passes
    private val banExpiryTicker = object : Runnable {
        override fun run() {
            evaluateCurrentBanState()
            mainHandler.postDelayed(this, 10000L) // checks every 10 seconds
        }
    }

    /**
     * Initializes GlobalAdminManager with application context.
     * Starts listening to global service switch and attaches auth listener for ban tracking.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (isInitialized.compareAndSet(false, true)) {
            startGlobalServiceListener()
            mainHandler.post(banExpiryTicker)

            // Attach Firebase Auth state change observer
            try {
                FirebaseAuth.getInstance().addAuthStateListener { auth ->
                    val user = auth.currentUser
                    onUserAuthChanged(user?.uid)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed adding auth state listener: ${e.message}")
            }
        }
    }

    /**
     * Attaches real-time snapshot listener on `app_settings/global`.
     */
    fun startGlobalServiceListener() {
        val ctx = appContext ?: return
        if (FirebaseApp.getApps(ctx).isEmpty()) {
            Log.w(TAG, "FirebaseApp is not initialized; delaying global settings listener.")
            return
        }

        try {
            val firestore = FirebaseFirestore.getInstance()
            val globalDocRef = firestore.collection(COLL_APP_SETTINGS).document(DOC_GLOBAL)

            globalSettingsListener?.remove()
            globalSettingsListener = globalDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Real-time listener error on app_settings/global: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val active = snapshot.getBoolean("isServiceActive") ?: true
                    val message = snapshot.getString("offlineMessage")
                        ?: snapshot.getString("message")
                        ?: "Service is currently offline for scheduled maintenance. Please check back later."
                    val paymentActive = snapshot.getBoolean("isPaymentSystemActive") ?: true
                    val updateActive = snapshot.getBoolean("isInAppUpdateActive") ?: true

                    _isServiceActive.value = active
                    _offlineMessage.value = message
                    _isPaymentSystemActive.value = paymentActive
                    _isInAppUpdateActive.value = updateActive
                    Log.i(
                        TAG,
                        "Real-time global service update: isServiceActive=$active, isPaymentSystemActive=$paymentActive, isInAppUpdateActive=$updateActive, message=$message"
                    )
                } else {
                    // Default to active if doc does not exist
                    _isServiceActive.value = true
                    _isPaymentSystemActive.value = true
                    _isInAppUpdateActive.value = true
                }
                updateBlockedState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach global settings snapshot listener: ${e.message}", e)
        }
    }

    /**
     * Re-attaches user ban listener whenever the signed-in Firebase user changes.
     */
    fun onUserAuthChanged(uid: String?) {
        trackedUid = uid
        userDocListener?.remove()
        userDocListener = null

        val ctx = appContext ?: return
        if (FirebaseApp.getApps(ctx).isEmpty()) return

        if (uid.isNullOrBlank()) {
            _banState.value = UserBanState.NotBanned
            updateBlockedState()
            return
        }

        try {
            val firestore = FirebaseFirestore.getInstance()
            val userDocRef = firestore.collection(COLL_USERS).document(uid)
            userDocListener = userDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "User ban listener error on users/$uid: ${error.message}")
                    return@addSnapshotListener
                }
                processUserBanSnapshot(snapshot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed attaching user ban listener: ${e.message}", e)
        }
    }

    /**
     * Evaluates ban status from a user document snapshot.
     */
    private fun processUserBanSnapshot(snapshot: DocumentSnapshot?) {
        if (snapshot == null || !snapshot.exists()) {
            _banState.value = UserBanState.NotBanned
            updateBlockedState()
            return
        }

        val banUntilTimestamp = extractBanUntilTimestamp(snapshot)
        val now = LicenseManager.getCurrentServerTimeMs()

        if (banUntilTimestamp != null && banUntilTimestamp > now) {
            val formatted = formatDateTime(banUntilTimestamp)
            val reason = snapshot.getString("banReason") ?: snapshot.getString("reason")
            _banState.value = UserBanState.Banned(
                banUntilTimestamp = banUntilTimestamp,
                formattedDateTime = formatted,
                reason = reason
            )
            Log.w(TAG, "User is currently BANNED until $formatted (ts: $banUntilTimestamp). Reason: $reason")
        } else {
            _banState.value = UserBanState.NotBanned
        }
        updateBlockedState()
    }

    /**
     * Safely parses `banUntil` field from Firestore.
     * Supports Firestore Timestamp, java.util.Date, Long epoch ms, or Number.
     * Null or absent fields return null (treated as not banned).
     */
    private fun extractBanUntilTimestamp(snapshot: DocumentSnapshot): Long? {
        return try {
            val timestampObj = snapshot.getTimestamp("banUntil")
            if (timestampObj != null) {
                return timestampObj.toDate().time
            }
            val dateObj = snapshot.getDate("banUntil")
            if (dateObj != null) {
                return dateObj.time
            }
            val longObj = snapshot.getLong("banUntil")
            if (longObj != null && longObj > 0L) {
                return longObj
            }
            val numberObj = snapshot.get("banUntil") as? Number
            numberObj?.toLong()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Ticker check: if user was banned but banUntil has now passed, automatically lift the ban.
     */
    private fun evaluateCurrentBanState() {
        val current = _banState.value
        if (current is UserBanState.Banned) {
            if (LicenseManager.getCurrentServerTimeMs() >= current.banUntilTimestamp) {
                Log.i(TAG, "User ban expired naturally at timestamp ${current.banUntilTimestamp}. Lifting ban.")
                _banState.value = UserBanState.NotBanned
                updateBlockedState()
            }
        }
    }

    private fun updateBlockedState() {
        val isServiceDown = !_isServiceActive.value
        val isBanned = _banState.value is UserBanState.Banned
        _isBlocked.value = isServiceDown || isBanned
    }

    /**
     * Evaluates whether automated background tasks, MacroDroid intents, or Accessibility clicks
     * are permitted to execute. Returns false if global service is offline or user is banned.
     */
    fun canExecuteActions(context: Context? = null): Boolean {
        if (!_isServiceActive.value) {
            Log.d(TAG, "Action blocked: isServiceActive is false.")
            return false
        }
        val currentBan = _banState.value
        if (currentBan is UserBanState.Banned) {
            if (LicenseManager.getCurrentServerTimeMs() < currentBan.banUntilTimestamp) {
                Log.d(TAG, "Action blocked: User is banned until ${currentBan.formattedDateTime}.")
                return false
            } else {
                _banState.value = UserBanState.NotBanned
                updateBlockedState()
            }
        }
        return true
    }

    private val dateTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    }

    /**
     * Formats an epoch timestamp into a friendly date/time string, e.g. "02 Nov 2026, 05:30 PM".
     */
    fun formatDateTime(timestampMs: Long): String {
        return try {
            val sdf = dateTimeFormat.get() ?: SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            sdf.format(Date(timestampMs))
        } catch (_: Exception) {
            Date(timestampMs).toString()
        }
    }
}
