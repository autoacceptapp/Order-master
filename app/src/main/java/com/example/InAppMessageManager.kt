package com.example

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.auth.GoogleAuthManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data model for In-App Popup Messages.
 */
data class InAppMessage(
    val id: String,
    val title: String,
    val message: String,
    val targetUid: String? = null,
    val timestamp: Long = 0L
)

/**
 * InAppMessageManager
 *
 * Real-time listener and manager for in-app popups and broadcast messages:
 * 1. Global Messages (`global_messages` collection) -> broadcast to all users, with strict
 *    defense-in-depth `targetUid` filtering (allows only blank/null, "ALL", or current user's UID).
 * 2. Targeted Messages (`users/{uid}/messages` subcollection) -> specific to current user.
 * 3. Deduplicates by `messageId` across both streams and records dismissed IDs in SharedPreferences.
 */
object InAppMessageManager {

    private const val TAG = "InAppMessageManager"
    private const val PREFS_NAME = "in_app_messages_prefs"
    private const val KEY_DISMISSED_IDS = "dismissed_message_ids"

    private const val COLL_GLOBAL_MESSAGES = "global_messages"
    private const val COLL_USERS = "users"
    private const val COLL_USER_MESSAGES = "messages"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isInitialized = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var bypassUiDelayForTesting: Boolean = false

    private var globalListener: ListenerRegistration? = null
    private var userMessageListener: ListenerRegistration? = null
    private var activeMessageUid: String? = null
    private val listenerLock = Any()

    // Cache of dismissed message IDs
    private val dismissedIds = mutableSetOf<String>()

    // Current message to display as an AlertDialog popup
    private val _activeMessage = MutableStateFlow<InAppMessage?>(null)
    val activeMessage: StateFlow<InAppMessage?> = _activeMessage.asStateFlow()

    // Queue of pending messages to display sequentially
    private val pendingQueue = mutableListOf<InAppMessage>()
    private val queueLock = Any()

    private fun isFirebaseReady(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        return try {
            FirebaseApp.initializeApp(context) != null
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseApp could not be initialized: ${e.message}")
            false
        }
    }

    /**
     * Strict defense-in-depth recipient check for `global_messages` documents.
     * Allows ONLY documents where `targetUid` is missing/blank, `"ALL"` (case-insensitive),
     * or exact-matches `currentUid`. Silently drops any document addressed to another user.
     */
    @VisibleForTesting
    internal fun isGlobalMessageAllowedForUser(targetUid: String?, currentUid: String?): Boolean {
        val normalizedTarget = targetUid?.trim()
        if (normalizedTarget.isNullOrEmpty()) return true
        if (normalizedTarget.equals("ALL", ignoreCase = true)) return true
        val normalizedCurrent = currentUid?.trim()
        return !normalizedCurrent.isNullOrEmpty() && normalizedTarget == normalizedCurrent
    }

    /**
     * Parses, recipient-filters, and enqueues a document from `global_messages`.
     * Returns `true` if the message was accepted and enqueued, or `false` if silently dropped,
     * blank, already dismissed, or deduplicated across streams.
     */
    @VisibleForTesting
    internal fun processGlobalMessageDocument(
        id: String,
        title: String?,
        message: String?,
        targetUid: String?,
        timestamp: Long,
        currentUid: String?
    ): Boolean {
        val cleanId = id.trim()
        val cleanMessage = message?.trim().orEmpty()
        if (cleanId.isEmpty() || cleanMessage.isEmpty()) return false

        if (!isGlobalMessageAllowedForUser(targetUid, currentUid)) {
            Log.d(TAG, "Silently dropped global_messages doc $cleanId addressed to another user.")
            return false
        }

        val inAppMsg = InAppMessage(
            id = cleanId,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Announcement",
            message = cleanMessage,
            targetUid = targetUid?.trim(),
            timestamp = timestamp
        )
        return enqueueMessage(inAppMsg)
    }

    /**
     * Parses and enqueues a document from `users/{uid}/messages`, sharing the exact same
     * `messageId` deduplication and dismissal pipeline as `global_messages`.
     */
    @VisibleForTesting
    internal fun processUserMessageDocument(
        id: String,
        title: String?,
        message: String?,
        targetUid: String?,
        timestamp: Long,
        currentUid: String
    ): Boolean {
        val cleanId = id.trim()
        val cleanMessage = message?.trim().orEmpty()
        val cleanCurrentUid = currentUid.trim()
        if (cleanId.isEmpty() || cleanMessage.isEmpty() || cleanCurrentUid.isEmpty()) return false

        if (!isGlobalMessageAllowedForUser(targetUid, cleanCurrentUid)) {
            Log.d(TAG, "Silently dropped user message doc $cleanId with mismatched targetUid.")
            return false
        }

        val inAppMsg = InAppMessage(
            id = cleanId,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Direct Message",
            message = cleanMessage,
            targetUid = targetUid?.trim()?.takeIf { it.isNotEmpty() } ?: cleanCurrentUid,
            timestamp = timestamp
        )
        return enqueueMessage(inAppMsg)
    }

    /**
     * Initializes the manager with the application context.
     * Loads dismissed IDs from SharedPreferences and sets up Firestore real-time listeners.
     */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx

        if (isInitialized.compareAndSet(false, true)) {
            loadDismissedIds(ctx)

            // Observe auth state strictly so Firestore listeners only attach after sign-in
            scope.launch {
                GoogleAuthManager.userAuthState.collect { authState ->
                    val uid = authState.uid ?: try {
                        if (isFirebaseReady(ctx)) {
                            FirebaseAuth.getInstance().currentUser?.uid
                        } else null
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not resolve current Firebase UID: ${e.message}")
                        null
                    }

                    if (authState.isLoggedIn && !uid.isNullOrBlank() && isFirebaseReady(ctx)) {
                        startGlobalMessageListener(ctx, uid)
                        startUserMessageListener(ctx, uid)
                    } else {
                        stopGlobalMessageListener()
                        stopUserMessageListener()
                        clearPendingQueueInMemory()
                    }
                }
            }
        }
    }

    private fun loadDismissedIds(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet(KEY_DISMISSED_IDS, emptySet()) ?: emptySet()
        synchronized(dismissedIds) {
            dismissedIds.clear()
            dismissedIds.addAll(savedSet)
        }
    }

    private fun markMessageDismissedLocally(messageId: String) {
        val snapshotSet = synchronized(dismissedIds) {
            dismissedIds.add(messageId)
            dismissedIds.toSet()
        }
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_DISMISSED_IDS, snapshotSet).apply()
    }

    fun isMessageDismissed(messageId: String): Boolean {
        return synchronized(dismissedIds) {
            dismissedIds.contains(messageId)
        }
    }

    /**
     * Called when the user clicks "Dismiss" on the popup.
     * Records the message ID locally so it is never shown again,
     * immediately dismisses the active dialog in Compose, and debounces
     * popping the next queued message to prevent AlertDialog state flicker or freeze.
     */
    fun dismissMessage(messageId: String) {
        markMessageDismissedLocally(messageId)

        synchronized(queueLock) {
            pendingQueue.removeAll { it.id == messageId }
        }

        // Step 1: Immediately clear active message so Compose cleans up the exiting dialog
        _activeMessage.value = null

        if (bypassUiDelayForTesting) {
            synchronized(queueLock) {
                val next = pendingQueue.firstOrNull { !isMessageDismissed(it.id) }
                if (next != null) {
                    _activeMessage.value = next
                }
            }
            return
        }

        // Step 2: Debounce popping the next queued message by 300ms to allow Compose dialog exit transition to finish cleanly
        scope.launch(Dispatchers.Main) {
            delay(300L)
            synchronized(queueLock) {
                val next = pendingQueue.firstOrNull { !isMessageDismissed(it.id) }
                if (next != null) {
                    _activeMessage.value = next
                    Log.d(TAG, "Popped next in-app message after debounce: ${next.id} - ${next.title}")
                }
            }
        }
    }

    /**
     * Enqueues a message to be shown if it hasn't been dismissed, isn't currently active,
     * and isn't already queued (deduplicating across `global_messages` and `users/{uid}/messages`).
     */
    private fun enqueueMessage(message: InAppMessage): Boolean {
        if (message.id.isBlank() || message.message.isBlank()) return false
        if (isMessageDismissed(message.id)) return false

        val shouldPromoteImmediately: Boolean
        synchronized(queueLock) {
            if (_activeMessage.value?.id == message.id || pendingQueue.any { it.id == message.id }) {
                return false
            }
            pendingQueue.add(message)
            if (bypassUiDelayForTesting && _activeMessage.value == null) {
                _activeMessage.value = pendingQueue.firstOrNull { !isMessageDismissed(it.id) }
                shouldPromoteImmediately = false
            } else {
                shouldPromoteImmediately = _activeMessage.value == null
            }
        }

        if (shouldPromoteImmediately) {
            scope.launch(Dispatchers.Main) {
                delay(150L)
                synchronized(queueLock) {
                    if (_activeMessage.value == null) {
                        _activeMessage.value = pendingQueue.firstOrNull { !isMessageDismissed(it.id) }
                    }
                }
            }
        }
        return true
    }

    private fun clearPendingQueueInMemory() {
        synchronized(queueLock) {
            pendingQueue.clear()
            _activeMessage.value = null
        }
    }

    /**
     * Real-time listener for `global_messages` collection.
     * Applies strict client-side `targetUid` recipient filtering so legacy targeted documents
     * addressed to other users are silently dropped.
     */
    private fun startGlobalMessageListener(context: Context, fallbackUid: String? = null) {
        if (!isFirebaseReady(context)) return
        val initialUid = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        } ?: fallbackUid
        if (initialUid.isNullOrBlank()) return

        synchronized(listenerLock) {
            if (globalListener != null) return
            try {
                val db = FirebaseFirestore.getInstance()
                globalListener = db.collection(COLL_GLOBAL_MESSAGES)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(10)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Global messages listener error: ${error.message}")
                            return@addSnapshotListener
                        }

                        val liveCurrentUid = try {
                            FirebaseAuth.getInstance().currentUser?.uid
                        } catch (_: Exception) {
                            null
                        } ?: activeMessageUid ?: initialUid

                        snapshot?.documents?.forEach { doc ->
                            val timestamp = doc.getTimestamp("timestamp")?.toDate()?.time
                                ?: doc.getLong("timestamp")
                                ?: 0L
                            processGlobalMessageDocument(
                                id = doc.id,
                                title = doc.getString("title"),
                                message = doc.getString("message"),
                                targetUid = doc.getString("targetUid"),
                                timestamp = timestamp,
                                currentUid = liveCurrentUid
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start global message listener: ${e.message}", e)
            }
        }
    }

    /**
     * Real-time listener for targeted messages: `users/{uid}/messages`
     */
    private fun startUserMessageListener(context: Context, uid: String) {
        if (!isFirebaseReady(context) || uid.isBlank()) return

        synchronized(listenerLock) {
            if (activeMessageUid == uid && userMessageListener != null) return
            try {
                val db = FirebaseFirestore.getInstance()
                userMessageListener?.remove()
                activeMessageUid = uid
                userMessageListener = db.collection(COLL_USERS)
                    .document(uid)
                    .collection(COLL_USER_MESSAGES)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(10)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "User messages listener error: ${error.message}")
                            return@addSnapshotListener
                        }

                        snapshot?.documents?.forEach { doc ->
                            val timestamp = doc.getTimestamp("timestamp")?.toDate()?.time
                                ?: doc.getLong("timestamp")
                                ?: 0L
                            processUserMessageDocument(
                                id = doc.id,
                                title = doc.getString("title"),
                                message = doc.getString("message"),
                                targetUid = doc.getString("targetUid"),
                                timestamp = timestamp,
                                currentUid = uid
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start user message listener: ${e.message}", e)
            }
        }
    }

    private fun stopGlobalMessageListener() {
        synchronized(listenerLock) {
            globalListener?.remove()
            globalListener = null
        }
    }

    private fun stopUserMessageListener() {
        synchronized(listenerLock) {
            userMessageListener?.remove()
            userMessageListener = null
            activeMessageUid = null
        }
    }

    @VisibleForTesting
    internal fun getPendingQueueSnapshotForTesting(): List<InAppMessage> {
        return synchronized(queueLock) { pendingQueue.toList() }
    }

    @VisibleForTesting
    internal fun resetForTesting(context: Context? = null, bypassUiDelay: Boolean = true) {
        stopGlobalMessageListener()
        stopUserMessageListener()
        bypassUiDelayForTesting = bypassUiDelay
        synchronized(queueLock) {
            pendingQueue.clear()
            _activeMessage.value = null
        }
        synchronized(dismissedIds) {
            dismissedIds.clear()
        }
        if (context != null) {
            appContext = context.applicationContext
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        } else {
            appContext = null
        }
    }
}
