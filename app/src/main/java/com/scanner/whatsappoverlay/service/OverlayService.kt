package com.scanner.whatsappoverlay.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.scanner.whatsappoverlay.MainActivity
import com.scanner.whatsappoverlay.R
import kotlin.math.abs

/**
 * OverlayService
 *
 * Security & Systems Engineering:
 * - Anti-Tapjacking: Enforces FLAG_NOT_FOCUSABLE and FLAG_LAYOUT_IN_SCREEN
 * - Direction Control: Toggles scroll direction (DOWN vs UP)
 * - SKIP Action: Allows immediate bypass of paused media without manual intervention
 * - Live UI Performance Telemetry: Shows node search time in milliseconds
 */
class OverlayService : Service(), ChatScannerService.Companion.ScanStateListener {

    companion object {
        private const val TAG = "OverlayServiceSecurity"
        private const val NOTIFICATION_CHANNEL_ID = "overlay_service_fgs_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.scanner.whatsappoverlay.ACTION_START_OVERLAY"
        const val ACTION_STOP = "com.scanner.whatsappoverlay.ACTION_STOP_OVERLAY"

        @Volatile
        var isOverlayRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    // Overlay View Elements
    private var btnToggleScan: View? = null
    private var tvScanStatus: TextView? = null
    private var ivStatusIcon: ImageView? = null
    private var btnSkip: Button? = null
    private var btnToggleDirection: Button? = null
    private var tvDetectionBadge: TextView? = null
    private var tvPerfMetric: TextView? = null
    private var btnCloseOverlay: View? = null
    private var seekSpeed: SeekBar? = null
    private var tvSpeedLabel: TextView? = null
    private var speedProgress = 80

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Floating scanner active"))
        isOverlayRunning = true

        ChatScannerService.setListener(this)
        initFloatingOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initFloatingOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.layout_floating_overlay, null)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // SECURITY: FLAG_NOT_FOCUSABLE prevents overlay from intercepting keystrokes/passwords
        // FLAG_LAYOUT_IN_SCREEN ensures overlay stays within screen bounds without tapjacking system UI
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 260
        }

        bindOverlayViews()
        setupDragTouchListener()

        windowManager.addView(overlayView, layoutParams)
        Log.i(TAG, "[Overlay] Floating view bound with anti-tapjacking flags.")
    }

    private fun bindOverlayViews() {
        overlayView?.let { root ->
            btnToggleScan = root.findViewById(R.id.btn_toggle_scan)
            tvScanStatus = root.findViewById(R.id.tv_scan_status)
            ivStatusIcon = root.findViewById(R.id.iv_status_icon)
            btnSkip = root.findViewById(R.id.btn_skip)
            btnToggleDirection = root.findViewById(R.id.btn_toggle_direction)
            tvDetectionBadge = root.findViewById(R.id.tv_detection_badge)
            tvPerfMetric = root.findViewById(R.id.tv_perf_metric)
            btnCloseOverlay = root.findViewById(R.id.btn_close_overlay)
            seekSpeed = root.findViewById(R.id.seek_speed)
            tvSpeedLabel = root.findViewById(R.id.tv_speed_label)

            // Speed slider: right = faster swipes (150ms), left = slower (1500ms)
            seekSpeed?.progress = speedProgress
            applySpeed(speedProgress)
            seekSpeed?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedProgress = progress
                    applySpeed(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            // Start / Pause Toggle
            btnToggleScan?.setOnClickListener {
                toggleScanner()
            }

            // SKIP Button: Skip current paused media and resume immediately
            btnSkip?.setOnClickListener {
                val scanner = ChatScannerService.instance
                if (scanner != null) {
                    scanner.skipCurrentMediaAndResume()
                    tvDetectionBadge?.visibility = View.GONE
                    btnSkip?.visibility = View.GONE
                    updateNotification("Moving on...")
                }
            }

            // Toggle Direction (Scroll DOWN vs Scroll UP)
            btnToggleDirection?.setOnClickListener {
                val scanner = ChatScannerService.instance
                val current = scanner?.getScrollDirection() ?: ChatScannerService.ScrollDirection.DOWN
                val newDirection = if (current == ChatScannerService.ScrollDirection.DOWN) {
                    ChatScannerService.ScrollDirection.UP
                } else {
                    ChatScannerService.ScrollDirection.DOWN
                }

                scanner?.setScrollDirection(newDirection)
                updateDirectionButtonUI(newDirection)
            }

            btnCloseOverlay?.setOnClickListener {
                ChatScannerService.instance?.stopScanning("Closed from overlay")
                stopSelf()
            }
        }
    }

    private fun applySpeed(progress: Int) {
        val ms = (1500 - progress * 13.5).toLong()
        ChatScannerService.instance?.setScrollSpeed(ms)
        tvSpeedLabel?.text = when {
            progress < 25 -> "Speed: Slow"
            progress < 65 -> "Speed: Medium"
            else -> "Speed: Fast"
        }
    }

    private fun updateDirectionButtonUI(direction: ChatScannerService.ScrollDirection) {
        btnToggleDirection?.post {
            if (direction == ChatScannerService.ScrollDirection.DOWN) {
                btnToggleDirection?.text = "SCROLL DOWN ↓"
                btnToggleDirection?.setTextColor(Color.parseColor("#38BDF8"))
            } else {
                btnToggleDirection?.text = "SCROLL UP ↑"
                btnToggleDirection?.setTextColor(Color.parseColor("#A855F7"))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragTouchListener() {
        val dragHandle = overlayView?.findViewById<View>(R.id.layout_drag_handle) ?: overlayView

        dragHandle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            private val CLICK_DRAG_TOLERANCE = 12f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy

                        overlayView?.let {
                            if (it.isAttachedToWindow) {
                                windowManager.updateViewLayout(it, layoutParams)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        if (diffX < CLICK_DRAG_TOLERANCE && diffY < CLICK_DRAG_TOLERANCE) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun toggleScanner() {
        val scanner = ChatScannerService.instance
        if (scanner == null) {
            tvScanStatus?.text = "Enable Accessibility"
            return
        }

        if (scanner.isCurrentlyScanning()) {
            scanner.stopScanning("Paused via overlay")
        } else {
            scanner.startScanning()
        }
    }

    // =========================================================================
    // Callbacks from ChatScannerService
    // =========================================================================

    override fun onScanningStateChanged(isScanning: Boolean) {
        tvScanStatus?.post {
            if (isScanning) {
                applySpeed(speedProgress)
                tvDetectionBadge?.visibility = View.GONE
                tvScanStatus?.text = "Pause"
                btnToggleScan?.setBackgroundColor(Color.parseColor("#DC2626")) // Red (Pause)
                ivStatusIcon?.setImageResource(android.R.drawable.ic_media_pause)
                btnSkip?.visibility = View.GONE
                updateNotification("Auto-scanning WhatsApp...")
            } else {
                tvScanStatus?.text = "Start Auto-Scan"
                btnToggleScan?.setBackgroundColor(Color.parseColor("#059669")) // Emerald (Start)
                ivStatusIcon?.setImageResource(android.R.drawable.ic_media_play)

                // Show SKIP button if scanner paused on a detected media item
                if (ChatScannerService.instance?.hasPausedMediaToSkip() == true) {
                    btnSkip?.visibility = View.VISIBLE
                }
                updateNotification("Scanner paused")
            }
        }
    }

    override fun onMediaDetected(fileSize: String, bounds: Rect, signature: String) {
        tvDetectionBadge?.post {
            tvDetectionBadge?.visibility = View.VISIBLE
            tvDetectionBadge?.text = fileSize
            btnSkip?.visibility = View.VISIBLE
        }
        updateNotification("Paused on media: $fileSize")
    }

    override fun onScanPerformance(durationMs: Double) {
        tvPerfMetric?.post {
            tvPerfMetric?.text = String.format("Scan: %.1fms", durationMs)
            if (durationMs > 16.0) {
                tvPerfMetric?.setTextColor(Color.parseColor("#F59E0B")) // Amber warning
            } else {
                tvPerfMetric?.setTextColor(Color.parseColor("#10B981")) // Emerald good
            }
        }
    }

    override fun onScanLog(message: String) {
        Log.d(TAG, "[Log] $message")
    }

    // =========================================================================
    // Notification & Teardown
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WhatsApp Scanner Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // SECURE INTENT: Explicit class Intent prevents component hijacking
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WhatsApp Media Scanner")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    override fun onDestroy() {
        ChatScannerService.setListener(null)
        overlayView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        overlayView = null
        isOverlayRunning = false
        super.onDestroy()
    }
}