package com.ideliver.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Notification-listener access is a system-granted toggle — it cannot be
 * requested with a runtime permission dialog. The app can only report whether
 * it is on and deep-link the user to the system screen to turn it on.
 */
object NotificationAccess {

    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /** Opens system "Notification access", deep-linking to this listener on R+. */
    fun settingsIntent(context: Context): Intent {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = ComponentName(context, NotificationCaptureService::class.java)
            intent.putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component.flattenToString(),
            )
        }
        return intent
    }
}
