package com.ideliver.data

/**
 * Shared launch contract for the odometer camera screen. Lives here so the
 * background capture service can build a PendingIntent to the (app-module)
 * activity by name, without a compile-time dependency on the app module.
 */
object MileageCaptureContract {
    const val ACTIVITY_CLASS = "com.ideliver.mileage.MileageCaptureActivity"
    const val EXTRA_KIND = "kind"
}
