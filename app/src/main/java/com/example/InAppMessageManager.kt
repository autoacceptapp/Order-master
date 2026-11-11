package com.example

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
 * 1. Global Messages (`global_messages` collection) -> broadcast to all users.
 * 2. Targeted Messages (`users/{uid}/messages` subcollection) -> specific to current user.
 * 3. Records dismissed message IDs in SharedPreferences to avoid showing duplicates.
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

    private var globalListener: ListenerRegistration? = null
    private var userMessageListener: ListenerRegistration? = null

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
     * Initializes the manager with the application context.
     * Loads dismissed IDs from SharedPreferences and sets up Firestore real-time listeners.
     */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx

        if (isInitialized.compareAndSet(false, true)) {
            loadDismissedIds(ctx)

            // Start global message listener if Firebase is ready, or retry gracefully
            if (isFirebaseReady(ctx)) {
                startGlobalMessageListener(ctx)
            }

            // Observe auth state to attach/detach user-targeted message listener
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

                    if (isFirebaseReady(ctx)) {
                        startGlobalMessageListener(ctx)
                    }

                    if (authState.isLoggedIn && !uid.isNullOrBlank()) {
                        startUserMessageListener(ctx, uid)
                    } else {
                        stopUserMessageListener()
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
        val ctx = appContext ?: return
        synchronized(dismissedIds) {
            dismissedIds.add(messageId)
        }
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_DISMISSED_IDS, synchronized(dismissedIds) { dismissedIds.toSet() }).apply()
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
     * Enqueues a message to be shown if it hasn't been dismissed yet.
     */
    private fun enqueueMessage(message: InAppMessage) {
        if (isMessageDismissed(message.id)) return

        synchronized(queueLock) {
            if (pendingQueue.none { it.id == message.id }) {
                pendingQueue.add(message)
            }
        }

        if (_activeMessage.value == null) {
            scope.launch(Dispatchers.Main) {
                delay(150L)
                synchronized(queueLock) {
                    if (_activeMessage.value == null) {
                        _activeMessage.value = pendingQueue.firstOrNull { !isMessageDismissed(it.id) }
                    }
                }
            }
        }
    }

    /**
     * Real-time listener for `global_messages` collection.
     */
    private fun startGlobalMessageListener(context: Context) {
        if (!isFirebaseReady(context)) return

        try {
            val db = FirebaseFirestore.getInstance()
            globalListener?.remove()
            globalListener = db.collection(COLL_GLOBAL_MESSAGES)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Global messages listener error: ${error.message}")
                        return@addSnapshotListener
                    }

                    snapshot?.documents?.forEach { doc ->
                        val id = doc.id
                        val title = doc.getString("title") ?: "Announcement"
                        val message = doc.getString("message") ?: ""
                        val targetUid = doc.getString("targetUid")
                        val timestamp = doc.getTimestamp("timestamp")?.toDate()?.time
                            ?: doc.getLong("timestamp")
                            ?: 0L

                        if (message.isNotBlank()) {
                            val inAppMsg = InAppMessage(
                                id = id,
                                title = title,
                                message = message,
                                targetUid = targetUid,
                                timestamp = timestamp
                            )
                            enqueueMessage(inAppMsg)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start global message listener: ${e.message}", e)
        }
    }

    /**
     * Real-time listener for targeted messages: `users/{uid}/messages`
     */
    private fun startUserMessageListener(context: Context, uid: String) {
        if (!isFirebaseReady(context) || uid.isBlank()) return

        try {
            val db = FirebaseFirestore.getInstance()
            userMessageListener?.remove()
            userMessageListener = db.collection(COLL_USERS)
                .document(uid)
                .collection(COLL_USER_MESSAGES)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "User messages listener error: ${error.message}")
                        return@addSnapshotListener
                    }

                    snapshot?.documents?.forEach { doc ->
                        val id = doc.id
                        val title = doc.getString("title") ?: "Direct Message"
                        val message = doc.getString("message") ?: ""
                        val targetUid = doc.getString("targetUid") ?: uid
                        val timestamp = doc.getTimestamp("timestamp")?.toDate()?.time
                            ?: doc.getLong("timestamp")
                            ?: 0L

                        if (message.isNotBlank()) {
                            val inAppMsg = InAppMessage(
                                id = id,
                                title = title,
                                message = message,
                                targetUid = targetUid,
                                timestamp = timestamp
                            )
                            enqueueMessage(inAppMsg)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start user message listener: ${e.message}", e)
        }
    }

    private fun stopUserMessageListener() {
        userMessageListener?.remove()
        userMessageListener = null
    }
}
