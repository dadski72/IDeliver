package com.ideliver.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ideliver.data.MileageCaptureContract
import com.ideliver.data.MileageKind

/**
 * Heads-up prompt shown when a dash starts or ends, offering to capture the
 * odometer. Its kind ([MileageKind.START] / [MileageKind.END]) is decided by the
 * event that fired it — start at dash-start, end at dash-end — so the tap always
 * opens the correct capture screen.
 *
 * The camera cannot be opened from the background, so this is a tap-to-open
 * prompt: tapping it launches the (app-module) capture activity, referenced by
 * name via [MileageCaptureContract] to avoid a dependency on the app module.
 */
object MileagePrompt {

    private const val CHANNEL_ID = "mileage_prompt"
    private const val NOTIF_ID_BASE = 4200

    fun promptFor(context: Context, kind: MileageKind) {
        // Inline guard (lint requires the check in this method, not a helper).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val launch = Intent().apply {
            setClassName(context.packageName, MileageCaptureContract.ACTIVITY_CLASS)
            putExtra(MileageCaptureContract.EXTRA_KIND, kind.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            kind.ordinal,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, text) = when (kind) {
            MileageKind.START -> "Starting a dash?" to "Capture your start odometer"
            MileageKind.END -> "Dash ended" to "Capture your end odometer"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(android.R.drawable.ic_menu_camera, "Capture", pending)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_BASE + kind.ordinal, notification) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Odometer prompts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Offers to capture your odometer when a dash starts or ends."
        }
        manager.createNotificationChannel(channel)
    }
}
