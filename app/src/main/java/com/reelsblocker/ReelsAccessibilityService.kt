package com.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service that monitors Instagram for Reels navigation.
 *
 * Once the overlay is shown, it stays until the user taps "Go Back".
 * After dismissal, redirects user to Instagram DMs and enters cooldown.
 */
class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val COOLDOWN_AFTER_DISMISS_MS = 3000L

        // Reels keywords — matched against content descriptions, text, view IDs
        private val REELS_KEYWORDS = listOf(
            "reel",
            "reels",
            "clips",
            "clips_viewer",
            "reels_viewer",
            "reels_tab",
            "clips_tab",
            "short_video",        // Some regions use this
            "explore_reel",
            "reel_viewer"
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
    private var lastTreeDumpTime = 0L

    override fun onCreate() {
        super.onCreate()
        OverlayManager.onDismissed = {
            Log.d(TAG, "User dismissed overlay — redirecting to Instagram DMs")

            // Open Instagram DMs to navigate away from Reels completely
            try {
                val dmIntent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct_inbox"))
                dmIntent.setPackage(INSTAGRAM_PACKAGE)
                dmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(dmIntent)
            } catch (e: Exception) {
                Log.w(TAG, "DM deep link failed, trying fallback: ${e.message}")
                // Fallback: open Instagram main screen
                try {
                    val fallback = packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE)
                    fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    fallback?.let { startActivity(it) }
                } catch (_: Exception) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }

            inCooldown = true
            handler.postDelayed({
                inCooldown = false
                Log.d(TAG, "Cooldown ended")
            }, COOLDOWN_AFTER_DISMISS_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != INSTAGRAM_PACKAGE) return

        if (!PrefsManager.isBlockingEnabled(this)) {
            if (OverlayManager.isShowing) OverlayManager.dismiss()
            return
        }
        if (OverlayManager.isShowing) return
        if (inCooldown) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                checkForReels(event)
            }
        }
    }

    private fun checkForReels(event: AccessibilityEvent) {
        // Signal 1: Check event metadata
        val eventClass = event.className?.toString()?.lowercase() ?: ""
        val eventDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""

        if (matchesReels(eventClass, eventDesc, eventText)) {
            Log.d(TAG, "✅ REELS via event — class=$eventClass desc=$eventDesc text=$eventText")
            showOverlay()
            return
        }

        // Signal 2: Scan node tree
        val rootNode = try { rootInActiveWindow } catch (_: Exception) { null }
        if (rootNode != null) {
            try {
                if (scanNode(rootNode, 0)) {
                    showOverlay()
                    return
                }

                // Dump tree periodically for debugging (max once every 5 seconds)
                val now = System.currentTimeMillis()
                if (now - lastTreeDumpTime > 5000 && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    lastTreeDumpTime = now
                    Log.d(TAG, "--- TREE DUMP (no Reels detected) ---")
                    dumpTree(rootNode, 0)
                    Log.d(TAG, "--- END TREE DUMP ---")
                }
            } finally {
                try { rootNode.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun matchesReels(className: String, desc: String, text: String): Boolean {
        return REELS_KEYWORDS.any { kw ->
            desc.contains(kw) || text.contains(kw) || className.contains(kw)
        } || REELS_CLASS_FRAGMENTS.any { frag ->
            className.contains(frag)
        }
    }

    private fun scanNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 10) return false

        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Check all signals
        val kwMatch = REELS_KEYWORDS.any { kw ->
            contentDesc.contains(kw) || text.contains(kw) || viewId.contains(kw)
        }
        val classMatch = REELS_CLASS_FRAGMENTS.any { className.contains(it) }

        if (kwMatch || classMatch) {
            Log.d(TAG, "🎯 Match at depth=$depth: desc=\"$contentDesc\" text=\"$text\" id=$viewId class=$className selected=${node.isSelected}")
            return true
        }

        // Recurse
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                if (scanNode(child, depth + 1)) return true
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Dump the full accessibility tree to logcat for debugging.
     */
    private fun dumpTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 6) return
        val indent = "  ".repeat(depth)
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val cls = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val sel = if (node.isSelected) " [SEL]" else ""
        val click = if (node.isClickable) " [CLICK]" else ""

        if (desc.isNotEmpty() || text.isNotEmpty() || viewId.isNotEmpty()) {
            Log.d(TAG, "${indent}cls=$cls id=$viewId desc=\"$desc\" text=\"$text\"$sel$click")
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                dumpTree(child, depth + 1)
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun showOverlay() {
        if (!OverlayManager.isShowing) {
            Log.d(TAG, "🚫 SHOWING OVERLAY")
            OverlayManager.show(this)
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        OverlayManager.onDismissed = null
        OverlayManager.dismiss()
    }
}
