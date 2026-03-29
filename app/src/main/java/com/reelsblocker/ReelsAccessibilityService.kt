package com.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service that monitors Instagram for Reels navigation.
 *
 * KEY DESIGN: Once the overlay is shown, it stays until the user taps "Go Back".
 * We do NOT auto-dismiss based on tree scanning (because the overlay covers Instagram,
 * making the tree scan return false — which would incorrectly dismiss the overlay).
 *
 * After the user dismisses the overlay, we press Back to navigate away from Reels,
 * then resume monitoring for the next time they navigate to Reels.
 */
class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        // Cooldown after user dismisses overlay before we can re-trigger (ms)
        private const val COOLDOWN_AFTER_DISMISS_MS = 3000L

        // Keywords that indicate Reels content
        private val REELS_KEYWORDS = listOf(
            "reel",
            "reels",
            "clips",
            "clips_viewer",
            "clip_viewer",
            "reels_viewer",
            "reels_tab",
            "clips_tab"
        )

        // Class name fragments for Reels components
        private val REELS_CLASS_FRAGMENTS = listOf(
            "clips",
            "reels",
            "reel_viewer",
            "clip_viewer"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var inCooldown = false

    override fun onCreate() {
        super.onCreate()

        // When user taps "Go Back" on the overlay
        OverlayManager.onDismissed = {
            Log.d(TAG, "User dismissed overlay — pressing Back and entering cooldown")

            // Press Back to navigate away from Reels
            performGlobalAction(GLOBAL_ACTION_BACK)

            // Enter cooldown so we don't immediately re-trigger
            inCooldown = true
            handler.postDelayed({
                inCooldown = false
                Log.d(TAG, "Cooldown ended — monitoring resumed")
            }, COOLDOWN_AFTER_DISMISS_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName != INSTAGRAM_PACKAGE) return

        // If blocking is disabled, dismiss any showing overlay
        if (!PrefsManager.isBlockingEnabled(this)) {
            if (OverlayManager.isShowing) OverlayManager.dismiss()
            return
        }

        // If overlay is already showing, do nothing — it stays until user dismisses
        if (OverlayManager.isShowing) return

        // If in cooldown after dismissal, don't re-trigger
        if (inCooldown) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                checkForReels(event)
            }
        }
    }

    /**
     * Check multiple signals for Reels content.
     */
    private fun checkForReels(event: AccessibilityEvent) {
        // Signal 1: Check the event itself
        val eventClass = event.className?.toString()?.lowercase() ?: ""
        val eventDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""

        val eventMatch = REELS_KEYWORDS.any { kw ->
            eventClass.contains(kw) || eventDesc.contains(kw) || eventText.contains(kw)
        } || REELS_CLASS_FRAGMENTS.any { frag ->
            eventClass.contains(frag)
        }

        if (eventMatch) {
            Log.d(TAG, "✅ REELS via event — class=$eventClass desc=$eventDesc")
            showOverlay()
            return
        }

        // Signal 2: Scan the node tree
        if (scanNodeTree()) {
            Log.d(TAG, "✅ REELS via node tree")
            showOverlay()
        }
    }

    /**
     * Scan the accessibility node tree for Reels indicators.
     */
    private fun scanNodeTree(): Boolean {
        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return false

        return try {
            scanNode(rootNode, depth = 0)
        } finally {
            try { rootNode.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Recursively scan nodes, depth limited to 8.
     */
    private fun scanNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 8) return false

        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Check content description, text, view ID, and class name for Reels signals
        val descMatch = REELS_KEYWORDS.any { contentDesc.contains(it) || text.contains(it) }
        val viewIdMatch = REELS_KEYWORDS.any { viewId.contains(it) }
        val classMatch = REELS_CLASS_FRAGMENTS.any { className.contains(it) }

        if (viewIdMatch || classMatch) {
            // View ID or class name match = strong signal, always trigger
            return true
        }

        if (descMatch) {
            // Content desc match — only trigger if it's a selected tab or a non-interactive label
            if (node.isSelected) return true
            if (!node.isClickable) return true
            // Clickable + selected = reels tab is active
            if (node.isClickable && node.isSelected) return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            try {
                if (scanNode(child, depth + 1)) return true
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }

        return false
    }

    private fun showOverlay() {
        if (!OverlayManager.isShowing) {
            Log.d(TAG, "🚫 SHOWING OVERLAY — stays until user dismisses")
            OverlayManager.show(this)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        OverlayManager.onDismissed = null
        OverlayManager.dismiss()
    }
}
