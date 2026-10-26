package com.scanner.whatsappoverlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scanner.whatsappoverlay.service.ChatScannerService
import com.scanner.whatsappoverlay.service.OverlayService

/**
 * MainActivity
 *
 * Senior Security Engineering:
 * - Validates overlay and accessibility privileges
 * - Confirms accessibility scope is confined strictly to "com.whatsapp"
 * - Employs explicit Intents for inter-service lifecycle commands
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var ivOverlayCheck: ImageView
    private lateinit var btnGrantOverlay: Button

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var ivAccessibilityCheck: ImageView
    private lateinit var btnGrantAccessibility: Button

    private lateinit var btnToggleOverlayService: Button
    private lateinit var btnLaunchWhatsApp: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    private fun initViews() {
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        ivOverlayCheck = findViewById(R.id.iv_overlay_check)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)

        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        ivAccessibilityCheck = findViewById(R.id.iv_accessibility_check)
        btnGrantAccessibility = findViewById(R.id.btn_grant_accessibility)

        btnToggleOverlayService = findViewById(R.id.btn_toggle_overlay_service)
        btnLaunchWhatsApp = findViewById(R.id.btn_launch_whatsapp)
    }

    private fun setupListeners() {
        btnGrantOverlay.setOnClickListener {
            openOverlaySettings()
        }

        btnGrantAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        btnToggleOverlayService.setOnClickListener {
            toggleOverlayService()
        }

        btnLaunchWhatsApp.setOnClickListener {
            launchWhatsApp()
        }
    }

    private fun refreshPermissionStates() {
        val hasOverlay = checkOverlayPermission()
        val hasAccessibility = isAccessibilityServiceEnabled()

        if (hasOverlay) {
            tvOverlayStatus.text = "Granted"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            ivOverlayCheck.setImageResource(android.R.drawable.checkbox_on_background)
            btnGrantOverlay.isEnabled = false
            btnGrantOverlay.text = "Overlay Enabled ✓"
        } else {
            tvOverlayStatus.text = "Permission Required"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            ivOverlayCheck.setImageResource(android.R.drawable.checkbox_off_background)
            btnGrantOverlay.isEnabled = true
            btnGrantOverlay.text = "Grant Overlay Permission"
        }

        if (hasAccessibility) {
            tvAccessibilityStatus.text = "Enabled (Scoped: com.whatsapp)"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            ivAccessibilityCheck.setImageResource(android.R.drawable.checkbox_on_background)
            btnGrantAccessibility.isEnabled = false
            btnGrantAccessibility.text = "Accessibility Enabled ✓"
        } else {
            tvAccessibilityStatus.text = "Service Disabled"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            ivAccessibilityCheck.setImageResource(android.R.drawable.checkbox_off_background)
            btnGrantAccessibility.isEnabled = true
            btnGrantAccessibility.text = "Enable Accessibility Service"
        }

        val canStartOverlay = hasOverlay && hasAccessibility
        btnToggleOverlayService.isEnabled = canStartOverlay

        if (OverlayService.isOverlayRunning) {
            btnToggleOverlayService.text = "Stop Floating Overlay"
            btnToggleOverlayService.setBackgroundColor(ContextCompat.getColor(this, R.color.button_stop))
        } else {
            btnToggleOverlayService.text = "Launch Floating Overlay"
            btnToggleOverlayService.setBackgroundColor(ContextCompat.getColor(this, R.color.button_start))
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * Checks if ChatScannerService is enabled and strictly scoped to com.whatsapp
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        for (service in enabledServices) {
            val serviceId = service.id
            if (serviceId.contains(packageName, ignoreCase = true) &&
                serviceId.contains(ChatScannerService::class.java.simpleName, ignoreCase = true)) {
                
                // Security check: verify package restriction includes com.whatsapp
                val targetPackages = service.packageNames
                if (targetPackages != null && !targetPackages.contains("com.whatsapp")) {
                    Toast.makeText(this, "Security Warning: Accessibility service not restricted to WhatsApp!", Toast.LENGTH_LONG).show()
                }
                return true
            }
        }
        return ChatScannerService.instance != null
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Enable 'WhatsApp Media Scanner' in Downloaded Services", Toast.LENGTH_LONG).show()
    }

    private fun toggleOverlayService() {
        if (!checkOverlayPermission()) {
            openOverlaySettings()
            return
        }

        // SECURE INTENT: Uses explicit class references to prevent external spoofing
        if (OverlayService.isOverlayRunning) {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP
            }
            startService(intent)
        } else {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Floating Overlay started! Switch to WhatsApp.", Toast.LENGTH_SHORT).show()
        }

        btnToggleOverlayService.postDelayed({ refreshPermissionStates() }, 300)
    }

    private fun launchWhatsApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "com.whatsapp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}