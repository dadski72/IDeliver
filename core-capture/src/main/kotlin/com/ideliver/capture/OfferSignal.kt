package com.ideliver.capture

import android.service.notification.StatusBarNotification

/**
 * Tiny in-process handshake between the two capture paths. The notification
 * listener is the reliable *trigger* — an offer notification means an offer
 * screen is up — so it marks a timestamp here, and the accessibility service
 * only snapshots the screen while that mark is fresh. This keeps the node-tree
 * dump focused on the offer screen instead of every DoorDash screen the driver
 * touches (and the customer PII on them).
 */
object OfferSignal {

    /** How long after an offer notification the offer screen is considered live. */
    const val WINDOW_MS = 20_000L

    @Volatile
    private var lastOfferAt: Long = 0L

    @Volatile
    var lastStore: String? = null
        private set

    fun markOffer(store: String?) {
        lastOfferAt = System.currentTimeMillis()
        lastStore = store
    }

    fun isOfferLive(now: Long = System.currentTimeMillis()): Boolean =
        lastOfferAt != 0L && now - lastOfferAt <= WINDOW_MS

    /**
     * True when a notification looks like a fresh delivery offer. DoorDash uses
     * a "New Delivery!" title with an "_ORDER" key (verified from real capture);
     * the key check is the resilient half if the copy changes.
     */
    fun isOfferNotification(sbn: StatusBarNotification): Boolean {
        val title = sbn.notification.extras?.getCharSequence("android.title")?.toString()
        return sbn.key.contains("_ORDER") || title == "New Delivery!"
    }
}
