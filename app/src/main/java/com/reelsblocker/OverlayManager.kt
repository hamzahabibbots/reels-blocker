package com.reelsblocker

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/**
 * Manages the full-screen system overlay that blocks Instagram Reels.
 * Uses SYSTEM_ALERT_WINDOW to draw on top of Instagram.
 */
object OverlayManager {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    val isShowing: Boolean get() = overlayView != null

    /**
     * Show the blocking overlay on top of any app.
     */
    fun show(context: Context) {
        if (overlayView != null) return // Already showing

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.overlay_block, null)

        // Set up the dismiss button
        overlayView?.findViewById<View>(R.id.btnDismiss)?.setOnClickListener {
            dismiss()
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            overlayView = null
        }
    }

    /**
     * Dismiss the blocking overlay.
     */
    fun dismiss() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            overlayView = null
            windowManager = null
        }
    }
}
