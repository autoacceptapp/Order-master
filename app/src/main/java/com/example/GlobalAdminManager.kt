package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
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

    // Listener registration for app_settings/global
    private val listenerLock = Any()
    private var globalSettingsListener: ListenerRegistration? = null

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

    // State: User Ban (synchronized from LicenseManager's single users/{uid} listener)
    private val _banState = MutableStateFlow<UserBanState>(UserBanState.NotBanned)
    val banState: StateFlow<UserBanState> = _banState.asStateFlow()

    // Combined Blocked State: either service is offline or user is banned
    private val _isBlocked = MutableStateFlow(false)
    val isBlocked: StateFlow<Boolean> = _isBlocked.asStateFlow()

    /**
     * Initializes GlobalAdminManager with application context and starts listening to app_settings/global.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (isInitialized.compareAndSet(false, true)) {
            startGlobalServiceListener()
        }
    }

    /**
     * Attaches real-time snapshot listener on `app_settings/global`.
     */
    fun startGlobalServiceListener() {
        val ctx = appContext ?: LicenseManager.getAppContext() ?: return
        if (FirebaseApp.getApps(ctx).isEmpty()) {
            Log.w(TAG, "FirebaseApp is not initialized; delaying global settings listener.")
            return
        }

        synchronized(listenerLock) {
            if (globalSettingsListener != null) return
            try {
                val firestore = FirebaseFirestore.getInstance()
                val globalDocRef = firestore.collection(COLL_APP_SETTINGS).document(DOC_GLOBAL)

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
                        LicenseManager.setServiceActiveState(active)
                        Log.i(
                            TAG,
                            "Real-time global service update: isServiceActive=$active, isPaymentSystemActive=$paymentActive, isInAppUpdateActive=$updateActive, message=$message"
                        )
                    } else {
                        // Default to active if doc does not exist
                        _isServiceActive.value = true
                        _isPaymentSystemActive.value = true
                        _isInAppUpdateActive.value = true
                        LicenseManager.setServiceActiveState(true)
                    }
                    updateBlockedState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach global settings snapshot listener: ${e.message}", e)
            }
        }
    }

    /**
     * Detaches global settings listener during teardown/tests.
     */
    fun detachGlobalServiceListener() {
        synchronized(listenerLock) {
            globalSettingsListener?.remove()
            globalSettingsListener = null
        }
    }

    /**
     * Synchronizes user ban state directly from LicenseManager's single `users/{uid}` snapshot listener and ticker,
     * eliminating duplicate Firestore listeners and duplicate periodic handlers on `users/{uid}`.
     */
    fun syncBanStateFromLicenseManager(banUntilTimestamp: Long?, reason: String?) {
        val now = LicenseManager.getCurrentServerTimeMs()
        if (banUntilTimestamp != null && banUntilTimestamp > now) {
            val formatted = if (banUntilTimestamp == Long.MAX_VALUE) {
                "Permanent"
            } else {
                formatDateTime(banUntilTimestamp)
            }
            _banState.value = UserBanState.Banned(
                banUntilTimestamp = banUntilTimestamp,
                formattedDateTime = formatted,
                reason = reason
            )
        } else {
            _banState.value = UserBanState.NotBanned
        }
        updateBlockedState()
    }

    private fun updateBlockedState() {
        val isServiceDown = !_isServiceActive.value
        val isBanned = _banState.value is UserBanState.Banned
        _isBlocked.value = isServiceDown || isBanned
    }

    @VisibleForTesting
    internal fun setBanStateForTesting(state: UserBanState) {
        _banState.value = state
        updateBlockedState()
    }

    /**
     * Evaluates whether automated background tasks, MacroDroid intents, or Accessibility clicks
     * are permitted to execute. Performs a live comparison against `LicenseManager.getCurrentServerTimeMs()`
     * so Doze-mode delays on background timers never allow banned sessions or retain expired bans.
     */
    fun canExecuteActions(context: Context? = null): Boolean {
        if (!_isServiceActive.value) {
            Log.d(TAG, "Action blocked: isServiceActive is false.")
            return false
        }
        if (LicenseManager.isClockTamperingDetected(context)) {
            Log.d(TAG, "Action blocked: Clock tampering detected.")
            return false
        }
        val now = LicenseManager.getCurrentServerTimeMs()
        val currentBan = _banState.value
        if (currentBan is UserBanState.Banned) {
            if (now < currentBan.banUntilTimestamp) {
                Log.d(TAG, "Action blocked: User is banned until ${currentBan.formattedDateTime}.")
                return false
            } else {
                Log.i(TAG, "Live ban check expired banUntil=${currentBan.banUntilTimestamp} at now=$now. Lifting ban.")
                _banState.value = UserBanState.NotBanned
                updateBlockedState()
            }
        }
        if (LicenseManager.isCurrentlyBanned()) {
            Log.d(TAG, "Action blocked: LicenseManager reports active ban.")
            return false
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
