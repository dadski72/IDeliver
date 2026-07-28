package com.ideliver.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Like [NotificationAccess], but for the accessibility path. It cannot be granted
 * programmatically — the app can only report status and deep-link to the system
 * Accessibility settings screen.
 */
object AccessibilityAccess {

    private fun component(context: Context) =
        ComponentName(context, OfferAccessibilityService::class.java)

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val target = component(context).flattenToString()
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        for (entry in splitter) {
            if (entry.equals(target, ignoreCase = true)) return true
        }
        return false
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
