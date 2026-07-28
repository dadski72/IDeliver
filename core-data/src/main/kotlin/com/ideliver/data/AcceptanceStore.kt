package com.ideliver.data

import android.content.Context

/**
 * Last acceptance rate read off the DoorDash Ratings screen (accessibility).
 * Persisted so the "decline budget" survives restarts; refreshed whenever the
 * driver views their ratings. Null until first seen.
 */
class AcceptanceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("acceptance", Context.MODE_PRIVATE)

    /** Current acceptance rate percent, or null if never captured. */
    fun rate(): Int? = prefs.getInt(KEY_RATE, -1).takeIf { it in 0..100 }

    fun setRate(percent: Int) {
        prefs.edit()
            .putInt(KEY_RATE, percent)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    private companion object {
        const val KEY_RATE = "rate"
        const val KEY_AT = "at"
    }
}
