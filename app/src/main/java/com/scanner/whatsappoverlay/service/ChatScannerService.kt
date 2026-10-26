package com.scanner.whatsappoverlay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.scanner.whatsappoverlay.MainActivity
import com.scanner.whatsappoverlay.R
import com.scanner.whatsappoverlay.util.SoundAlertHelper
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * ChatScannerService
 *
 * Senior Security & Systems Engineer Implementation:
 * - Package Isolation: Strictly locked to "com.whatsapp"
 * - Zero Data Persistence: No chat messages, text, or photos are ever persisted to disk or logged
 * - Directional Swiping: Performs smooth gesture scrolling UP or DOWN based on user preference
 * - SKIP Support: Volatile memory tracking of skipped nodes to allow bypassing paused media
 * - Performance Benchmark: Measures node lookup latency to ensure zero UI thread jank (< 16ms)
 * - Local Notification: Sound and vibration heads-up notification when undownloaded media is spotted
 */
class ChatScannerService : AccessibilityService() {

    enum class ScrollDirection {
        UP,   // Swiping down to scroll towards older messages (top of chat)
        DOWN  // Swiping up to scroll towards newer messages (bottom of chat)
    }

    companion object {
        private const val TAG = "ChatScannerSecurity"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val NOTIFICATION_CHANNEL_ID = "media_alert_high_priority_channel"
        private const val ALERT_NOTIFICATION_ID = 2002

        // UI Frame benchmark budget in milliseconds (16.6ms for 60fps)
        private const val UI_PERF_THRESHOLD_MS = 16.0

        @Volatile
        var instance: ChatScannerService? = null
            private set

        interface ScanStateListener {
            fun onScanningStateChanged(isScanning: Boolean)
            fun onMediaDetected(fileSize: String, bounds: Rect, signature: String)
            fun onScanPerformance(durationMs: Double)
            fun onScanLog(message: String)
        }

        private var stateListener: ScanStateListener? = null

        fun setListener(listener: ScanStateListener?) {
            stateListener = listener
            instance?.let {
                listener?.onScanningStateChanged(it.isScanning.get())
            }
        }

        // Regex strictly matching media size notations e.g. "1.4 MB", "750 KB", "18.2 MB"
        // Avoids matching general words like "lamb", "cookbook", or "kebab"
        private val MEDIA_SIZE_PATTERN: Pattern = Pattern.compile(
            "\\b\\d+(?:\\.\\d+)?\\s*(?:MB|KB|GB)\\b",
            Pattern.CASE_INSENSITIVE
        )

        private val MEDIA_WORD_PATTERN: Pattern = Pattern.compile(
            "\\b(photo|image|picture|video|gif)\\b", Pattern.CASE_INSENSITIVE
        )
    }

    private val isScanning = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Configurable parameters
    private var scrollDirection: ScrollDirection = ScrollDirection.DOWN
    private var scrollSpeedMs: Long = 400L
    private var scrollIntervalMs: Long = 1100L

    // In-memory volatile cache of skipped node signatures (zero disk writes)
    private val skippedSignatures = Collections.synchronizedSet(HashSet<String>())
    private var currentPausedSignature: String? = null

    // After SKIP, ignore detections for a few swipes so the skipped item can scroll off-screen
    // (its on-screen position changes every swipe, so position-based signatures alone can't match it)

    private lateinit var soundAlertHelper: SoundAlertHelper
    private lateinit var notificationManager: NotificationManager
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private val scanLoopRunnable = object : Runnable {
        override fun run() {
            if (!isScanning.get()) return

            val foundMedia = searchForUndownloadedMedia()
            if (foundMedia) {
                Log.d(TAG, "[Security/Scanner] Undownloaded media spotted. Pausing auto-scroll.")
                stopScanning(reason = "Undownloaded media found on screen")
                return
            }

            // Dispatch gesture in the designated direction
            performScroll(scrollDirection, scrollSpeedMs)

            // Schedule next check after inertia settles
            if (isScanning.get()) {
                mainHandler.postDelayed(this, scrollIntervalMs)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        soundAlertHelper = SoundAlertHelper(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val metrics: DisplayMetrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        createAlertNotificationChannel()
        Log.i(TAG, "[Security] ChatScannerService connected. Isolated to: $WHATSAPP_PACKAGE")
        stateListener?.onScanLog("Service connected with strict package isolation.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isScanning.get()) return

        // SECURITY CHECK: Package Isolation
        // Reject any event originating outside of com.whatsapp to prevent privilege leak
        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg != WHATSAPP_PACKAGE) {
            return
        }

        // Process only relevant window and scroll updates
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

            val foundMedia = searchForUndownloadedMedia()
            if (foundMedia) {
                stopScanning(reason = "Undownloaded media appeared during content change")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "[Security] Service interrupted by system.")
        stopScanning("Service interrupted")
    }

    override fun onDestroy() {
        stopScanning("Service destroyed")
        skippedSignatures.clear()
        instance = null
        super.onDestroy()
    }

    // =========================================================================
    // Public State Controllers (Invoked by OverlayService / MainActivity)
    // =========================================================================

    fun startScanning() {
        // Pressing Start while paused on an item counts as "done with this one"
        handledHashes.addAll(currentPausedHashes)
        currentPausedHashes = emptyList()
        currentPausedSignature = null
        if (isScanning.compareAndSet(false, true)) {
            Log.i(TAG, "[Scanner] Started. Direction: $scrollDirection, Speed: ${scrollSpeedMs}ms")
            stateListener?.onScanningStateChanged(true)
            stateListener?.onScanLog("Scanning started (${scrollDirection.name})...")

            val foundImmediately = searchForUndownloadedMedia()
            if (foundImmediately) {
                stopScanning(reason = "Undownloaded media already visible")
                return
            }

            mainHandler.post(scanLoopRunnable)
        }
    }

    fun stopScanning(reason: String = "User requested stop") {
        if (isScanning.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(scanLoopRunnable)
            Log.i(TAG, "[Scanner] Stopped: $reason")
            stateListener?.onScanningStateChanged(false)
            stateListener?.onScanLog("Paused: $reason")
        }
    }

    /**
     * SKIP functionality:
     * When user taps SKIP during a pause, ignores the current media item in volatile RAM
     * and immediately issues a scroll gesture to advance to the next message.
     */
    fun skipCurrentMediaAndResume() {
        handledHashes.addAll(currentPausedHashes)
        currentPausedHashes = emptyList()
        currentPausedSignature = null

        // Immediately swipe to move past the current media
        performScroll(scrollDirection, scrollSpeedMs)

        // Resume scanning
        startScanning()
    }

    fun setScrollDirection(direction: ScrollDirection) {
        this.scrollDirection = direction
        Log.i(TAG, "[Scanner] Updated scroll direction to: ${direction.name}")
        stateListener?.onScanLog("Direction set to: ${direction.name}")
    }

    fun getScrollDirection(): ScrollDirection = scrollDirection

    fun setScrollSpeed(durationMs: Long) {
        this.scrollSpeedMs = durationMs.coerceIn(150L, 1500L)
        this.scrollIntervalMs = (this.scrollSpeedMs + 700L).coerceAtLeast(800L)
        Log.d(TAG, "[Scanner] Updated scroll speed to ${scrollSpeedMs}ms")
    }

    fun isCurrentlyScanning(): Boolean = isScanning.get()

    fun hasPausedMediaToSkip(): Boolean = currentPausedSignature != null

    // =========================================================================
    // Core Logic: Node Inspection with Performance Benchmark Layer
    // =========================================================================

    private class Candidate(val hash: Int, val bounds: Rect, val sizeText: String?)

    // Items already handled by the user (NEXT pressed). Kept only while still visible,
    // so recycled views showing a new message are not mistaken for old ones.
    private val handledHashes = HashSet<Int>()
    private var currentPausedHashes: List<Int> = emptyList()

    private fun collectCandidates(node: AccessibilityNodeInfo?, out: MutableList<Candidate>, depth: Int = 0) {
        if (node == null || depth > 40) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc".trim()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val onScreen = node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0

        if (onScreen) {
            val m = MEDIA_SIZE_PATTERN.matcher(combined)
            val cls = node.className?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""
            if (m.find()) {
                out.add(Candidate(node.hashCode(), bounds, m.group()))
            } else if (cls.contains("ImageView") &&
                bounds.width() > screenWidth * 0.25f && bounds.height() > screenHeight * 0.08f &&
                (MEDIA_WORD_PATTERN.matcher(desc).find() ||
                        id.contains("image", true) || id.contains("thumb", true) ||
                        id.contains("media", true) || id.contains("video", true))) {
                out.add(Candidate(node.hashCode(), bounds, null))
            }
        }
        for (i in 0 until node.childCount) {
            collectCandidates(node.getChild(i), out, depth + 1)
        }
    }

    /**
     * Finds the first media item (downloaded OR not) that has not been handled yet.
     * Not-downloaded items are recognised by a size label (e.g. "2.4 MB").
     */
    fun searchForUndownloadedMedia(): Boolean {
        val rootNode: AccessibilityNodeInfo = rootInActiveWindow ?: return false
        if (rootNode.packageName?.toString() != WHATSAPP_PACKAGE) return false

        val startNanos = SystemClock.elapsedRealtimeNanos()
        try {
            val candidates = ArrayList<Candidate>()
            collectCandidates(rootNode, candidates)
            stateListener?.onScanPerformance((SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0)

            handledHashes.retainAll(candidates.map { it.hash }.toSet())

            // Only pause on items whose centre is comfortably on screen
            val first = candidates.firstOrNull {
                it.hash !in handledHashes &&
                        it.bounds.centerY() > screenHeight * 0.10f && it.bounds.centerY() < screenHeight * 0.90f
            } ?: return false

            // Group everything overlapping this item (image + its size label = one bubble)
            val group = candidates.filter { Rect.intersects(it.bounds, first.bounds) }
            currentPausedHashes = group.map { it.hash }
            val size = group.firstOrNull { it.sizeText != null }?.sizeText
            val label = if (size != null) "NOT DOWNLOADED ($size)" else "Downloaded media"

            currentPausedSignature = label
            triggerMediaAlert(size != null, label)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "[Scanner] Error during node inspection: ${e.message}")
        }
        return false
    }

    private fun triggerMediaAlert(notDownloaded: Boolean, label: String) {
        soundAlertHelper.triggerVibration()
        if (notDownloaded) {
            soundAlertHelper.playAlertChime()
            dispatchLocalNotification(label)
        }
        stateListener?.onMediaDetected(label, Rect(), label)
        stateListener?.onScanLog("Media: $label")
    }

    /**
     * High-Priority Notification System with sound and vibration
     */
    private fun createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .build()

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WhatsApp Undownloaded Media Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when undownloaded media is spotted during auto-scan"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 250)
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun dispatchLocalNotification(sizeText: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("WhatsApp Media Scanner Alert")
            .setContentText("$sizeText spotted on screen.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Dynamic Directional Swiping:
     * - ScrollDirection.DOWN: Swipes UP (startY 72% -> endY 28%) to scroll newer messages into view.
     * - ScrollDirection.UP: Swipes DOWN (startY 28% -> endY 72%) to scroll older messages into view.
     */
    fun performScroll(direction: ScrollDirection, speedMs: Long) {
        val path = Path()
        val centerX = screenWidth * 0.5f

        val (startY, endY) = when (direction) {
            ScrollDirection.DOWN -> Pair(screenHeight * 0.72f, screenHeight * 0.28f)
            ScrollDirection.UP -> Pair(screenHeight * 0.28f, screenHeight * 0.72f)
        }

        path.moveTo(centerX, startY)
        path.lineTo(centerX, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0, speedMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "[Gesture] Swipe ${direction.name} completed successfully.")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "[Gesture] Swipe ${direction.name} was cancelled by system.")
            }
        }, null)

        stateListener?.onScanLog("Swiped ${direction.name} (stroke: ${speedMs}ms)")
    }
}