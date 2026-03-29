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
 *
 * The overlay is PERSISTENT — it stays until the user taps "Go Back".
 * It also BLOCKS all touch events so the reel can't be interacted with.
 */
object OverlayManager {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    /** Callback invoked when the user dismisses the overlay */
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = overlayView != null

    /**
     * Show the blocking overlay on top of any app.
     * The overlay captures all touch events (no FLAG_NOT_FOCUSABLE).
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

        // KEY: No FLAG_NOT_FOCUSABLE — the overlay captures ALL touches
        // This prevents the user from scrolling Reels behind the overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv() and 0, // Ensure touch is modal
            PixelFormat.TRANSLUCENT
        )

        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.overlay_block, null)

        // Set up the dismiss button — only way to close the overlay
        overlayView?.findViewById<View>(R.id.btnDismiss)?.setOnClickListener {
            dismiss()
            onDismissed?.invoke()
        }

        // Consume all touch events on the overlay root so nothing passes through
        overlayView?.setOnTouchListener { _, _ -> true }

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
