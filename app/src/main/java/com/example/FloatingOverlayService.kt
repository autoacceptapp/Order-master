package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Production-ready Floating Overlay Window Service.
 *
 * Implements:
 * 1. WindowManager TYPE_APPLICATION_OVERLAY with custom draggable layout.
 * 2. Visual State indication:
 *    - Green: Full Automation Mode (Master ON + Filters Active + Auto Click Enabled)
 *    - Red: Voice-Only Mode (Auto Click Disabled / TTS Voice Announcement Only)
 * 3. Dynamic Center Text:
 *    - Price of the last accepted ride (e.g., "₹95")
 *    - Shows "--" if no order has been accepted yet in the current session.
 * 4. Conditional Lifecycle:
 *    - Automatically hides and destroys window when Master Toggle is OFF or Floating Toggle is disabled.
 * 5. Tap interaction:
 *    - Tap toggles between Full Automation and Voice-Only mode.
 *    - Long press brings Order Master to foreground.
 */
class FloatingOverlayService : Service() {

    companion object {
        const val TAG = "FloatingOverlayService"
        const val CHANNEL_ID = "order_master_overlay_fgs_channel"
        const val NOTIFICATION_ID = 2048

        const val ACTION_START = "com.example.ACTION_START_OVERLAY"
        const val ACTION_STOP = "com.example.ACTION_STOP_OVERLAY"
        const val ACTION_UPDATE_FARE = "com.example.ACTION_UPDATE_FARE"
        const val EXTRA_FARE = "extra_fare"

        // Mode Colors
        val COLOR_FULL_AUTO_BG = Color.parseColor("#10B981") // Emerald Green
        val COLOR_FULL_AUTO_BORDER = Color.parseColor("#047857")
        val COLOR_VOICE_ONLY_BG = Color.parseColor("#EF4444") // Coral Red
        val COLOR_VOICE_ONLY_BORDER = Color.parseColor("#B91C1C")

        /**
         * Safely starts the floating overlay service if permissions and toggles allow.
         */
        fun start(context: Context) {
            if (!PermissionUtils.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot start overlay: SYSTEM_ALERT_WINDOW permission missing")
                return
            }
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FloatingOverlayService", e)
            }
        }

        /**
         * Safely stops the floating overlay service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop FloatingOverlayService", e)
            }
        }

        /**
         * Communicates a newly accepted fare to update the center badge dynamically.
         */
        fun updateFare(context: Context, fare: Double) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_UPDATE_FARE
                putExtra(EXTRA_FARE, fare)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update overlay fare", e)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var overlayCard: LinearLayout? = null
    private var tvFare: TextView? = null
    private var tvMode: TextView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private var isViewAttached = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
        startAsForeground()

        buildOverlayView()
        observeSettingsAndFare()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Check if Master Automation or Floating Toggle are disabled
        val masterOn = AppSettings.isAutoAcceptEnabled(this)
        val overlayOn = AppSettings.isFloatingOverlayEnabled(this)
        val canDraw = PermissionUtils.canDrawOverlays(this)

        if (!masterOn || !overlayOn || !canDraw) {
            Log.i(TAG, "Overlay visibility criteria not satisfied on startCommand. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.hasExtra(EXTRA_FARE) == true) {
            val fare = intent.getDoubleExtra(EXTRA_FARE, -1.0)
            if (fare >= 0) {
                updateFareText(fare)
            }
        }

        ensureOverlayAttached()
        return START_STICKY
    }

    /**
     * Constructs the draggable overlay button programmatically.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView() {
        val sizePx = dpToPx(70f)

        // Root container for gesture hit testing and padding
        overlayRoot = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // Circular Floating Button
        overlayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER
            }
            setPadding(dpToPx(4f), dpToPx(6f), dpToPx(4f), dpToPx(6f))
            elevation = dpToPx(8f).toFloat()
        }

        // Center Dynamic Text (Last Accepted Fare)
        tvFare = TextView(this).apply {
            text = formatFare(AppSettings.getLastAcceptedFare())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
        }

        // Mode Status Badge (AUTO vs VOICE)
        tvMode = TextView(this).apply {
            text = if (AppSettings.isAutoClickEnabled(this@FloatingOverlayService)) "AUTO" else "VOICE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setPadding(dpToPx(4f), dpToPx(1f), dpToPx(4f), dpToPx(1f))
            background = createBadgeBackground()
        }

        overlayCard?.addView(tvFare)
        overlayCard?.addView(tvMode)
        overlayRoot?.addView(overlayCard)

        // Prepare Window Layout Params
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Position: right edge, ~30% from the top
        val initialX = screenWidth - sizePx - dpToPx(16f)
        val initialY = (screenHeight * 0.32f).roundToInt()

        windowLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        setupDragAndClick(sizePx)
        updateVisualState(AppSettings.isAutoClickEnabled(this))
    }

    /**
     * Implements responsive touch gestures:
     * - Smooth dragging across screen boundaries
     * - Short Click: Toggles Full Automation (Green) vs Voice-Only Mode (Red)
     * - Long Press: Opens Order Master app
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragAndClick(buttonSizePx: Int) {
        val root = overlayRoot ?: return
        val card = overlayCard ?: return
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L

        var longPressRunnable: Runnable? = null

        root.setOnTouchListener { _, event ->
            val params = windowLayoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()

                    // Visual touch down feedback
                    card.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()

                    // Schedule long press
                    longPressRunnable = Runnable {
                        if (!isDragging) {
                            performHapticFeedback(strong = true)
                            openOrderMasterApp()
                        }
                    }
                    longPressRunnable?.let { mainHandler.postDelayed(it, 600L) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    }

                    if (isDragging) {
                        val displayMetrics = resources.displayMetrics
                        val maxX = displayMetrics.widthPixels - buttonSizePx
                        val maxY = displayMetrics.heightPixels - buttonSizePx

                        val newX = (initialX + dx).coerceIn(0, maxX)
                        val newY = (initialY + dy).coerceIn(dpToPx(24f), maxY)

                        // Avoid redundant WindowManager updates if coordinates haven't changed
                        if (params.x != newX || params.y != newY) {
                            params.x = newX
                            params.y = newY
                            try {
                                windowManager?.updateViewLayout(overlayRoot, params)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to update view layout on drag", e)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    card.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()

                    val pressDuration = System.currentTimeMillis() - downTime
                    if (!isDragging && pressDuration < 450) {
                        // Regular Click Action: Toggle Mode
                        performHapticFeedback(strong = false)
                        toggleMode()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Toggles between Full Automation (Green) and Voice-Only (Red) mode.
     */
    private fun toggleMode() {
        if (!LicenseManager.isAccessGranted()) {
            Toast.makeText(this, "Subscription/Trial Expired! Tap & hold to open Store.", Toast.LENGTH_LONG).show()
            AppSettings.addLog(
                title = "Overlay Blocked - Pass Expired",
                message = "Automation clicks locked. Please renew pass in the Store.",
                severity = LogSeverity.WARNING
            )
            updateVisualState(false)
            return
        }

        val nextIsAutoClick = AppSettings.toggleAutoClick(this)
        updateVisualState(nextIsAutoClick)

        val message = if (nextIsAutoClick) {
            "Full Automation Active (Auto-Click ON)"
        } else {
            "Voice-Only Mode Active (TTS Spoken Alerts Only)"
        }

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        AppSettings.addLog(
            title = if (nextIsAutoClick) "Mode: Full Automation" else "Mode: Voice-Only",
            message = "Captain toggled automation mode via Floating Overlay button.",
            severity = LogSeverity.INFO
        )
    }

    /**
     * Updates the button background color and sub-badge:
     * - Green: Full Automation Mode
     * - Red: Voice-Only Mode
     * - Dark Red/Lock: Expired
     */
    private fun updateVisualState(isAutoClickEnabled: Boolean) {
        val isLicensed = LicenseManager.isAccessGranted()
        val effectiveAutoClick = isLicensed && isAutoClickEnabled

        val bgColor = if (effectiveAutoClick) COLOR_FULL_AUTO_BG else COLOR_VOICE_ONLY_BG
        val strokeColor = if (effectiveAutoClick) COLOR_FULL_AUTO_BORDER else COLOR_VOICE_ONLY_BORDER

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            setStroke(dpToPx(2f), strokeColor)
        }

        overlayCard?.background = bgDrawable
        tvMode?.text = when {
            !isLicensed -> "LOCK"
            effectiveAutoClick -> "AUTO"
            else -> "VOICE"
        }
    }

    /**
     * Formats fare into currency format (e.g., "₹95") or "--" if null.
     */
    private fun formatFare(fare: Double?): String {
        return if (fare != null && fare > 0) {
            "₹${fare.toInt()}"
        } else {
            "--"
        }
    }

    /**
     * Dynamically updates fare in center text.
     */
    private fun updateFareText(fare: Double?) {
        val text = formatFare(fare)
        tvFare?.text = text
        // Subtle pulse animation on new fare update
        tvFare?.animate()
            ?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(120)
            ?.withEndAction {
                tvFare?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
            }?.start()
    }

    /**
     * Observes reactive AppSettings & Session Fare StateFlows.
     * Automatically stops and hides the overlay when Master Toggle or Floating Toggle is disabled.
     */
    private fun observeSettingsAndFare() {
        serviceScope.launch {
            AppSettings.settingsState.collectLatest { state ->
                val masterOn = state.isAutoAcceptEnabled
                val overlayOn = state.isFloatingOverlayEnabled

                // Specification Rule 2: Floating Button must ONLY be visible when Master ON + Floating ON
                if (!masterOn || !overlayOn) {
                    Log.i(TAG, "Settings changed: Master=$masterOn, Overlay=$overlayOn. Stopping FloatingOverlayService.")
                    stopSelf()
                    return@collectLatest
                }

                updateVisualState(state.isAutoClickEnabled)
            }
        }

        serviceScope.launch {
            AppSettings.lastAcceptedFareFlow.collectLatest { fare ->
                updateFareText(fare)
            }
        }
    }

    /**
     * Attaches the overlay to WindowManager if not already attached.
     */
    private fun ensureOverlayAttached() {
        if (!isViewAttached && overlayRoot != null && windowLayoutParams != null && windowManager != null) {
            try {
                windowManager?.addView(overlayRoot, windowLayoutParams)
                isViewAttached = true
                Log.d(TAG, "Floating overlay view successfully added to WindowManager.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach overlay window to WindowManager", e)
            }
        }
    }

    /**
     * Removes the overlay view safely.
     */
    private fun removeOverlayView() {
        if (isViewAttached && overlayRoot != null) {
            try {
                windowManager?.removeView(overlayRoot)
                Log.d(TAG, "Floating overlay view removed from WindowManager.")
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view", e)
            } finally {
                isViewAttached = false
            }
        }
    }

    private fun openOrderMasterApp() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch main app from overlay", e)
        }
    }

    private fun performHapticFeedback(strong: Boolean) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (strong) {
                    VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (strong) 70 else 35)
            }
        } catch (_: Exception) {}
    }

    private fun createBadgeBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(6f).toFloat()
            setColor(Color.parseColor("#40000000")) // 25% black tint
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).roundToInt()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Order Master Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Order Master floating overlay button active over driver apps"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Order Master Overlay Active")
            .setContentText("Floating control button is active. Tap to open app.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        removeOverlayView()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.d(TAG, "FloatingOverlayService destroyed.")
    }
}
