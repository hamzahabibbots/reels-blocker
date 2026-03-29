package com.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service that monitors Instagram for Reels navigation.
 *
 * Detection strategy:
 * 1. Listens for TYPE_WINDOW_STATE_CHANGED and TYPE_WINDOW_CONTENT_CHANGED events
 *    from com.instagram.android
 * 2. Traverses the accessibility node tree looking for Reels indicators:
 *    - Content descriptions containing "Reels"
 *    - Tab selections for the Reels tab
 *    - Known Instagram Reels activity/fragment class names
 * 3. When Reels is detected → shows the blocking overlay
 * 4. When user navigates away from Reels → dismisses the overlay
 */
class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        // Known class names associated with Instagram Reels
        private val REELS_CLASS_INDICATORS = listOf(
            "clips_viewer",         // Internal Reels viewer fragment
            "reels",                // Generic reels reference
            "ClipsViewerFragment",  // Reels viewer fragment
            "ReelsFragment"         // Reels tab fragment
        )

        // Content description keywords that indicate Reels
        private val REELS_CONTENT_KEYWORDS = listOf(
            "reels",
            "reel",
            "clips"
        )

        // Tab-related content descriptions
        private val REELS_TAB_DESCRIPTIONS = listOf(
            "reels",
            "reels tab"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != INSTAGRAM_PACKAGE) return
        if (!PrefsManager.isBlockingEnabled(this)) {
            // If blocking is disabled, make sure overlay is dismissed
            if (OverlayManager.isShowing) {
                OverlayManager.dismiss()
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChange(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChange(event)
            }
        }
    }

    /**
     * Handle window state changes — detecting when Reels activities are launched.
     */
    private fun handleWindowStateChange(event: AccessibilityEvent) {
        val className = event.className?.toString()?.lowercase() ?: return

        // Check if the class name contains Reels indicators
        val isReelsActivity = REELS_CLASS_INDICATORS.any { indicator ->
            className.contains(indicator)
        }

        if (isReelsActivity) {
            Log.d(TAG, "Reels activity detected via class: $className")
            showOverlay()
            return
        }

        // Also check content description of the event
        val contentDesc = event.contentDescription?.toString()?.lowercase()
        if (contentDesc != null && REELS_CONTENT_KEYWORDS.any { contentDesc.contains(it) }) {
            Log.d(TAG, "Reels detected via content description: $contentDesc")
            showOverlay()
            return
        }

        // If we're on a different screen within Instagram, dismiss overlay
        if (!isReelsRelatedScreen()) {
            dismissOverlay()
        }
    }

    /**
     * Handle content changes — detecting Reels tab navigation via node tree.
     */
    private fun handleContentChange(event: AccessibilityEvent) {
        if (isReelsRelatedScreen()) {
            Log.d(TAG, "Reels detected via content tree scan")
            showOverlay()
        } else if (OverlayManager.isShowing) {
            // Small debounce — only dismiss if we're consistently not on Reels
            dismissOverlay()
        }
    }

    /**
     * Traverse the accessibility node tree to detect Reels-related content.
     */
    private fun isReelsRelatedScreen(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return try {
            checkNodeForReels(rootNode, depth = 0)
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Recursively check nodes for Reels indicators.
     * Limited to depth 5 to avoid performance issues.
     */
    private fun checkNodeForReels(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 5) return false

        // Check if this is the selected Reels tab
        val contentDesc = node.contentDescription?.toString()?.lowercase()
        if (contentDesc != null) {
            // Check for selected Reels tab
            if (node.isSelected && REELS_TAB_DESCRIPTIONS.any { contentDesc.contains(it) }) {
                return true
            }
            // Check for Reels content descriptions when the node is a visible element
            if (REELS_CONTENT_KEYWORDS.any { contentDesc.contains(it) } && node.isVisibleToUser) {
                // Additional check: is this a navigation/tab element?
                val className = node.className?.toString()?.lowercase() ?: ""
                if (className.contains("tab") || className.contains("button") || node.isSelected) {
                    return true
                }
            }
        }

        // Check view ID for Reels-related IDs
        val viewId = node.viewIdResourceName?.lowercase()
        if (viewId != null && REELS_CLASS_INDICATORS.any { viewId.contains(it) }) {
            return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (checkNodeForReels(child, depth + 1)) {
                    return true
                }
            } finally {
                child.recycle()
            }
        }

        return false
    }

    private fun showOverlay() {
        if (!OverlayManager.isShowing) {
            OverlayManager.show(this)
        }
    }

    private fun dismissOverlay() {
        if (OverlayManager.isShowing) {
            OverlayManager.dismiss()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        dismissOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissOverlay()
    }
}
