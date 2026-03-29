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
 * Detection strategy (multiple signals):
 * 1. Content descriptions containing reels/clips keywords
 * 2. Text labels in the UI containing reels keywords
 * 3. Selected bottom navigation tabs for Reels
 * 4. View resource IDs containing reels/clips identifiers
 * 5. Class names of known Reels activities/fragments
 *
 * Dismissal uses a debounce delay to avoid flickering.
 */
class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        // Enable this to log the full accessibility node tree (for debugging)
        private const val DEBUG_LOG_TREE = true

        // Delay before dismissing overlay (ms) — prevents flickering during page transitions
        private const val DISMISS_DELAY_MS = 1500L

        // How often we re-check the tree after detecting Reels (ms)
        private const val RECHECK_INTERVAL_MS = 2000L

        // Keywords in content descriptions, text, or view IDs that indicate Reels
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

        // Additional class name fragments that indicate Reels components
        private val REELS_CLASS_FRAGMENTS = listOf(
            "clips",
            "reels",
            "reel_viewer",
            "clip_viewer",
            "ClipsViewerFragment",
            "ReelViewerFragment",
            "ReelsFragment",
            "IgReelsFragment"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var isCurrentlyOnReels = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName != INSTAGRAM_PACKAGE) return

        if (!PrefsManager.isBlockingEnabled(this)) {
            if (OverlayManager.isShowing) {
                OverlayManager.dismiss()
            }
            return
        }

        // Log event type for debugging
        val eventTypeStr = AccessibilityEvent.eventTypeToString(event.eventType)
        Log.d(TAG, "Event: $eventTypeStr | class=${event.className} | desc=${event.contentDescription}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                checkForReels(event)
            }
        }
    }

    /**
     * Main detection entry point — checks multiple signals for Reels.
     */
    private fun checkForReels(event: AccessibilityEvent) {
        // Signal 1: Check the event itself
        val eventClass = event.className?.toString()?.lowercase() ?: ""
        val eventDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""

        val eventMatch = REELS_KEYWORDS.any { kw ->
            eventClass.contains(kw) || eventDesc.contains(kw) || eventText.contains(kw)
        } || REELS_CLASS_FRAGMENTS.any { frag ->
            eventClass.contains(frag.lowercase())
        }

        if (eventMatch) {
            Log.d(TAG, "✅ REELS DETECTED via event — class=$eventClass desc=$eventDesc text=$eventText")
            onReelsDetected()
            return
        }

        // Signal 2: Walk the node tree
        val treeResult = scanNodeTree()

        if (treeResult) {
            Log.d(TAG, "✅ REELS DETECTED via node tree scan")
            onReelsDetected()
        } else {
            onReelsNotDetected()
        }
    }

    /**
     * Scan the full accessibility node tree for Reels indicators.
     */
    private fun scanNodeTree(): Boolean {
        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get root node: ${e.message}")
            null
        } ?: return false

        return try {
            scanNode(rootNode, depth = 0)
        } finally {
            try { rootNode.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Recursively scan nodes. Depth limited to 8 levels.
     */
    private fun scanNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 8) return false

        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Debug logging — prints full tree to logcat
        if (DEBUG_LOG_TREE && depth <= 4) {
            val indent = "  ".repeat(depth)
            Log.v(TAG, "${indent}[depth=$depth] class=$className id=$viewId desc=\"$contentDesc\" text=\"$text\" selected=${node.isSelected} focused=${node.isFocused}")
        }

        // Check 1: Content description or text contains Reels keywords
        val descOrTextMatch = REELS_KEYWORDS.any { kw ->
            contentDesc.contains(kw) || text.contains(kw)
        }

        // Check 2: View ID contains Reels keywords
        val viewIdMatch = REELS_KEYWORDS.any { kw ->
            viewId.contains(kw)
        }

        // Check 3: Class name matches known Reels fragments
        val classMatch = REELS_CLASS_FRAGMENTS.any { frag ->
            className.contains(frag.lowercase())
        }

        // Match found — but apply context-aware filtering
        if (descOrTextMatch || viewIdMatch || classMatch) {
            // If it's a selected tab/button with Reels keyword → strong signal
            if (node.isSelected || node.isFocused) {
                Log.d(TAG, "🎯 Strong match: selected/focused Reels element — desc=\"$contentDesc\" id=$viewId")
                return true
            }

            // If view ID contains Reels → strong signal (means Instagram has a Reels view on screen)
            if (viewIdMatch) {
                Log.d(TAG, "🎯 Strong match: Reels view ID found — id=$viewId")
                return true
            }

            // If class name matches Reels fragments → strong signal
            if (classMatch) {
                Log.d(TAG, "🎯 Strong match: Reels class name — class=$className")
                return true
            }

            // Content description match on a clickable/navigation element
            if (descOrTextMatch && (node.isClickable || className.contains("button") ||
                        className.contains("tab") || className.contains("imageview"))) {
                // This could be just the Reels icon in the tab bar, only match if selected
                if (node.isSelected) {
                    Log.d(TAG, "🎯 Strong match: selected Reels tab — desc=\"$contentDesc\"")
                    return true
                }
                // Don't match unselected tab buttons — that would trigger on any IG screen
            } else if (descOrTextMatch) {
                // Non-interactive element with reels keyword in desc — probably a Reels view
                Log.d(TAG, "🎯 Match: Reels content on screen — desc=\"$contentDesc\" text=\"$text\"")
                return true
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue

            try {
                if (scanNode(child, depth + 1)) {
                    return true
                }
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }

        return false
    }

    /**
     * Called when Reels is detected — show overlay and cancel pending dismissals.
     */
    private fun onReelsDetected() {
        isCurrentlyOnReels = true

        // Cancel any pending dismissal
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null

        if (!OverlayManager.isShowing) {
            Log.d(TAG, "🚫 SHOWING OVERLAY")
            OverlayManager.show(this)
        }
    }

    /**
     * Called when Reels is NOT detected — schedule a delayed dismissal.
     * The delay prevents flickering during Instagram page transitions.
     */
    private fun onReelsNotDetected() {
        if (!isCurrentlyOnReels || !OverlayManager.isShowing) return

        // Only schedule dismissal if one isn't already pending
        if (dismissRunnable == null) {
            dismissRunnable = Runnable {
                // Double-check one more time before dismissing
                val stillOnReels = scanNodeTree()
                if (!stillOnReels) {
                    Log.d(TAG, "✅ Overlay dismissed — user left Reels")
                    isCurrentlyOnReels = false
                    OverlayManager.dismiss()
                } else {
                    Log.d(TAG, "↩️ Still on Reels — keeping overlay")
                    dismissRunnable = null
                }
            }
            handler.postDelayed(dismissRunnable!!, DISMISS_DELAY_MS)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun cleanup() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        isCurrentlyOnReels = false
        OverlayManager.dismiss()
    }
}
