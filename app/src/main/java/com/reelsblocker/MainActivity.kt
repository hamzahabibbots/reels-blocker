package com.reelsblocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Main activity with toggle switch and permission management.
 * Provides a premium dark-themed UI for controlling the Reels blocker.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var switchBlocking: MaterialSwitch
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusLabel: TextView
    private lateinit var statusDot: View
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        switchBlocking = findViewById(R.id.switchBlocking)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusLabel = findViewById(R.id.tvStatusLabel)
        statusDot = findViewById(R.id.statusDot)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)

        // Toggle switch listener
        switchBlocking.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBlockingEnabled(this, isChecked)
            updateUI()

            // Dismiss overlay immediately if user turns off blocking
            if (!isChecked && OverlayManager.isShowing) {
                OverlayManager.dismiss()
            }
        }

        // Permission row click listeners
        findViewById<View>(R.id.rowAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<View>(R.id.rowOverlay).setOnClickListener {
            openOverlaySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh state every time user comes back (e.g., from settings)
        switchBlocking.isChecked = PrefsManager.isBlockingEnabled(this)
        updateUI()
    }

    /**
     * Update all UI elements to reflect current state.
     */
    private fun updateUI() {
        val isEnabled = PrefsManager.isBlockingEnabled(this)
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)
        val isFullyActive = isEnabled && hasAccessibility && hasOverlay

        // Status text
        tvStatus.text = if (isFullyActive) {
            getString(R.string.status_active)
        } else {
            getString(R.string.status_inactive)
        }

        tvStatusLabel.text = if (isFullyActive) {
            getString(R.string.status_active)
        } else {
            getString(R.string.status_inactive)
        }

        // Status dot color
        statusDot.setBackgroundColor(
            if (isFullyActive) getColor(R.color.green_active)
            else getColor(R.color.accent_red)
        )

        // Accessibility status badge
        if (hasAccessibility) {
            tvAccessibilityStatus.text = getString(R.string.permission_granted)
            tvAccessibilityStatus.setTextColor(getColor(R.color.green_active))
        } else {
            tvAccessibilityStatus.text = getString(R.string.permission_required)
            tvAccessibilityStatus.setTextColor(getColor(R.color.orange_warning))
        }

        // Overlay status badge
        if (hasOverlay) {
            tvOverlayStatus.text = getString(R.string.permission_granted)
            tvOverlayStatus.setTextColor(getColor(R.color.green_active))
        } else {
            tvOverlayStatus.text = getString(R.string.permission_required)
            tvOverlayStatus.setTextColor(getColor(R.color.orange_warning))
        }
    }

    /**
     * Check if our accessibility service is currently enabled.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${ReelsAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }

    /**
     * Open the Android Accessibility Settings screen.
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Open the "Draw Over Other Apps" settings screen for this app.
     */
    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
