package com.ideliver.capture

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Collections

/**
 * Primary capture path: the system-bound notification listener.
 *
 * Phase 1 responsibility is narrow — record fixtures for the target driver apps
 * and nothing else. No parsing, no overlay, no verdicts wired in yet.
 *
 * Post-vs-update is inferred by tracking keys seen since the listener connected:
 * DoorDash/Uber update an offer notification in place (same key, new content) as
 * the timer counts down, and each of those updates is a distinct fixture worth
 * keeping. Active notifications present at connect time are seeded so they are
 * not misreported as fresh posts.
 */
class NotificationCaptureService : NotificationListenerService() {

    private lateinit var store: DumpStore
    private val seenKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // The dashing status notification flickers (removes + re-posts itself), so a
    // new "dash started" only logs after a real gap in activity, not per flicker.
    private var lastDashActivityAt = 0L
    private var dashActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        store = DumpStore(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        seenKeys.clear()
        val active = runCatching { activeNotifications }.getOrNull().orEmpty().filterNotNull()
        active.forEach { it.key?.let(seenKeys::add) }
        // If a dash is already running when we (re)connect, adopt it silently so
        // we don't fire a spurious "dash started".
        dashActive = active.any { it.packageName in FixtureDump.TARGET_PACKAGES && isDashStatus(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!FixtureDump.ENABLED) return
        sbn ?: return
        if (sbn.packageName !in FixtureDump.TARGET_PACKAGES) return

        // "Dash started" marker, keyed on *presence*, not a time gap: the dashing
        // notification only pings every ~10 min, so a gap heuristic misfired on
        // every ping. Fire once on the transition into a dash; the periodic pings
        // just keep it alive and cancel any pending "ended".
        if (isDashStatus(sbn)) {
            lastDashActivityAt = System.currentTimeMillis()
            mainHandler.removeCallbacksAndMessages(null) // still dashing — cancel pending end
            if (!dashActive) {
                dashActive = true
                DashState.totalCents = null // fresh dash, running total starts over
                EventLog.add(this, "${platformLabel(sbn)} dash started")
                MileagePrompt.promptFor(this, com.ideliver.data.MileageKind.START)
            }
        }

        // Drop the navigation / foreground-service firehose; keep offers.
        if (!FixtureDump.shouldCapture(sbn)) return

        val isUpdate = !seenKeys.add(sbn.key)

        // Reliable trigger: tell the accessibility path an offer screen is live
        // so it can snapshot where the pay actually renders.
        if (OfferSignal.isOfferNotification(sbn)) {
            val storeName = parseStore(sbn)
            OfferSignal.markOffer(storeName)
            // Announce once per offer, not on every countdown update.
            if (!isUpdate) {
                EventLog.add(this, offerReceivedLine(sbn, storeName))
            }
        }

        val line = runCatching { DumpRecords.build(this, sbn, isUpdate) }.getOrNull() ?: return
        store.append(line)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val wasKnownOffer = seenKeys.remove(sbn.key)
        if (wasKnownOffer && OfferSignal.isOfferNotification(sbn)) {
            EventLog.add(this, "${platformLabel(sbn)} offer ended — ${parseStore(sbn) ?: "unknown store"}")
            com.ideliver.overlay.OverlayController.dismiss(this)
        }

        // Best-effort "dash ended": the dashing notification flickers, so only
        // conclude the dash is over if nothing reappears within the debounce.
        if (isDashStatus(sbn) && dashActive) {
            val label = platformLabel(sbn)
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                if (dashActive &&
                    System.currentTimeMillis() - lastDashActivityAt >= DASH_END_DEBOUNCE_MS
                ) {
                    dashActive = false
                    val total = DashState.totalCents
                        ?.let { " · $" + "%.2f".format(it / 100.0) + " this dash" }
                        .orEmpty()
                    EventLog.add(this, "$label dash ended$total")
                    DashState.totalCents = null
                    MileagePrompt.promptFor(this, com.ideliver.data.MileageKind.END)
                }
            }, DASH_END_DEBOUNCE_MS)
        }
    }

    private fun offerReceivedLine(sbn: StatusBarNotification, store: String?): String {
        val stops = parseStops(sbn)
        val stopText = if (stops != null && stops > 1) " · $stops stops" else ""
        // Pay/miles/mode arrive as a follow-up "DD offer — …" line from the
        // accessibility screen-read; the notification alone can't carry them.
        return "${platformLabel(sbn)} offer received — ${store ?: "unknown store"}$stopText"
    }

    private fun platformLabel(sbn: StatusBarNotification): String =
        if (sbn.packageName == "com.doordash.driverapp") "DD" else "UE"

    /**
     * DoorDash's persistent "you're dashing" notification. Verified copy:
     * title "DoorDash Driver Dash" / text "You're still dashing…". Uber's online
     * marker is unknown, so this currently only detects DoorDash.
     */
    private fun isDashStatus(sbn: StatusBarNotification): Boolean {
        val ex = sbn.notification.extras ?: return false
        val title = ex.getCharSequence("android.title")?.toString()
        val text = ex.getCharSequence("android.text")?.toString().orEmpty()
        return title == "DoorDash Driver Dash" || text.contains("dashing", ignoreCase = true)
    }

    /** "New Order: Go to Chick-fil-A" -> "Chick-fil-A"; strips the multi-stop suffix. */
    private fun parseStore(sbn: StatusBarNotification): String? {
        val text = sbn.notification.extras?.getCharSequence("android.text")?.toString() ?: return null
        return text
            .substringAfter("Go to ", text)
            .substringBefore(" and ")
            .trim()
            .ifEmpty { null }
    }

    /** DoorDash multi-store text reads "... and N other store(s)". */
    private fun parseStops(sbn: StatusBarNotification): Int? {
        val text = sbn.notification.extras?.getCharSequence("android.text")?.toString() ?: return null
        val n = Regex("""and (\d+) other store""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return if (n != null) n + 1 else null
    }

    private companion object {
        // How long the dashing notification must stay gone to count as ended.
        // DoorDash's dashing notification flickers (backgrounding, between orders),
        // so this is deliberately long — one real dash shouldn't fragment into
        // many. A real end stays gone well past this; the trade-off is the
        // end-of-dash odometer prompt lands ~3 min after you actually stop.
        const val DASH_END_DEBOUNCE_MS = 180_000L
    }
}
