package com.reelsblocker

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences wrapper for persisting the blocking toggle state.
 */
object PrefsManager {

    private const val PREFS_NAME = "reels_blocker_prefs"
    private const val KEY_BLOCKING_ENABLED = "blocking_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBlockingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCKING_ENABLED, false)

    fun setBlockingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCKING_ENABLED, enabled).apply()
    }
}
