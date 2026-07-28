package com.ideliver.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * "Display over other apps" is a special permission granted on a system screen;
 * it cannot be requested with a runtime dialog. Report status and deep-link.
 */
object OverlayAccess {

    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
