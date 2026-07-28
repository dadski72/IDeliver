package com.ideliver.data

import android.content.Context
import com.ideliver.model.RuleSettings

/**
 * Simple SharedPreferences-backed store for the driver's rule settings. A few
 * scalar values, read on demand — no need for a database table or a Flow.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("rule_settings", Context.MODE_PRIVATE)

    fun load(): RuleSettings = RuleSettings(
        homeBase = prefs.getString(KEY_HOME_BASE, null)?.ifBlank { null },
        radiusMiles = prefs.getFloat(KEY_RADIUS, 0f).toDouble(),
        maxMinutes = prefs.getInt(KEY_MAX_MINUTES, 0),
        baseFloorCents = prefs.getInt(KEY_BASE_FLOOR_CENTS, 200),
        basePerMileCents = prefs.getInt(KEY_BASE_PERMILE_CENTS, 15),
        deadheadFactor = prefs.getFloat(KEY_DEADHEAD, 1.0f).toDouble(),
        minDollarsPerHour = prefs.getFloat(KEY_MIN_PER_HOUR, 0f).toDouble(),
        minDollarsPerMile = prefs.getFloat(KEY_MIN_PER_MILE, 0f).toDouble(),
        platinumTargetPercent = prefs.getInt(KEY_PLATINUM_TARGET, 70),
        byTimeHourlyCents = prefs.getInt(KEY_BYTIME_HOURLY, 1300),
    )

    fun voiceEnabled(): Boolean = prefs.getBoolean(KEY_VOICE, true)

    fun setVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    fun save(settings: RuleSettings) {
        prefs.edit()
            .putString(KEY_HOME_BASE, settings.homeBase)
            .putFloat(KEY_RADIUS, settings.radiusMiles.toFloat())
            .putInt(KEY_MAX_MINUTES, settings.maxMinutes)
            .putInt(KEY_BASE_FLOOR_CENTS, settings.baseFloorCents)
            .putInt(KEY_BASE_PERMILE_CENTS, settings.basePerMileCents)
            .putFloat(KEY_DEADHEAD, settings.deadheadFactor.toFloat())
            .putFloat(KEY_MIN_PER_HOUR, settings.minDollarsPerHour.toFloat())
            .putFloat(KEY_MIN_PER_MILE, settings.minDollarsPerMile.toFloat())
            .putInt(KEY_PLATINUM_TARGET, settings.platinumTargetPercent)
            .apply()
    }

    private companion object {
        const val KEY_HOME_BASE = "home_base"
        const val KEY_RADIUS = "radius_miles"
        const val KEY_MAX_MINUTES = "max_minutes"
        const val KEY_BASE_FLOOR_CENTS = "base_floor_cents"
        const val KEY_BASE_PERMILE_CENTS = "base_permile_cents"
        const val KEY_DEADHEAD = "deadhead_factor"
        const val KEY_MIN_PER_HOUR = "min_per_hour"
        const val KEY_MIN_PER_MILE = "min_per_mile"
        const val KEY_PLATINUM_TARGET = "platinum_target"
        const val KEY_BYTIME_HOURLY = "bytime_hourly"
        const val KEY_VOICE = "voice_enabled"
    }
}
