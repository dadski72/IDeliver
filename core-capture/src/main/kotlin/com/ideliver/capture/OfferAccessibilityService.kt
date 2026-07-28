package com.ideliver.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import com.ideliver.data.AcceptanceStore
import com.ideliver.data.SettingsStore
import com.ideliver.model.CaptureSource
import com.ideliver.model.Decision
import com.ideliver.model.Offer
import com.ideliver.model.Platform
import com.ideliver.overlay.OverlayContent
import com.ideliver.overlay.OverlayController
import com.ideliver.parse.EarnMode
import com.ideliver.parse.OfferScreenParser
import com.ideliver.parse.ParsedOfferScreen
import com.ideliver.rules.RulesEngine
import java.time.Instant
import java.util.Collections

/**
 * Secondary capture path (enrichment): reads the on-screen offer to recover the
 * pay/miles/time the notification never carries. CLAUDE.md treats this as fragile
 * and expiring (Android 17 revokes non-tool accessibility access) — the app must
 * stay functional without it, so this only ever *adds* fixtures.
 *
 * HARD RULE (CLAUDE.md #1): this service is strictly read-only. It never calls
 * performAction, never clicks, never automates DoorDash or Uber. It snapshots the
 * node tree and nothing else — that is what keeps the driver's account alive.
 *
 * Snapshots are gated by [OfferSignal]: it only dumps while an offer notification
 * is fresh, so it captures the offer screen rather than every screen the driver
 * opens. Dumps are throttled and capped per offer to avoid a firehose.
 */
class OfferAccessibilityService : AccessibilityService() {

    private lateinit var store: DumpStore

    private var lastDumpAt = 0L
    private var offerWindowStart = 0L
    private var dumpsThisOffer = 0

    private var lastDropoffCheckAt = 0L
    private var lastDropoff: String? = null
    private var lastLoggedDashTotal: Int? = null
    private var lastLoggedAcceptance: Int? = null
    private var lastModeActiveMin: Int? = null

    // Offer UUIDs already logged with pay, so an offer's ~15 snapshots log once.
    private val emittedOfferIds: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val screenshotIo = Executors.newSingleThreadExecutor { r -> Thread(r, "offer-screenshot") }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = DumpStore(this, filePrefix = "a11y")
        VoiceSpeaker.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!FixtureDump.ENABLED) return
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in FixtureDump.TARGET_PACKAGES) return

        val now = System.currentTimeMillis()

        // Post-accept: not in an offer window. Look for the dropoff-nav screen and
        // log the delivery's city/ZIP once. (Cheap; no raw-screen dump here — we
        // only extract city + ZIP, never the customer's full street address.)
        if (!OfferSignal.isOfferLive(now)) {
            if (now - lastDropoffCheckAt < DROPOFF_CHECK_INTERVAL_MS) return
            lastDropoffCheckAt = now
            val root = rootInActiveWindow ?: return
            val texts = runCatching { A11yNodeDump.collectTexts(root) }.getOrDefault(emptyList())
            maybeLogDashTotal(texts)
            maybeCaptureAcceptance(texts)
            maybeLogModeAdvisor(texts)
            maybeLogDropoff(texts)
            return
        }

        // Reset the per-offer cap when a new offer window opens.
        if (now - offerWindowStart > OfferSignal.WINDOW_MS) {
            offerWindowStart = now
            dumpsThisOffer = 0
        }
        if (dumpsThisOffer >= MAX_DUMPS_PER_OFFER) return
        if (now - lastDumpAt < MIN_DUMP_INTERVAL_MS) return

        val root = rootInActiveWindow ?: return

        val texts = runCatching { A11yNodeDump.collectTexts(root) }.getOrDefault(emptyList())
        maybeLogPricedOffer(texts)
        maybeLogDashTotal(texts)

        val line = runCatching { A11yNodeDump.build(pkg, root) }.getOrNull() ?: return
        store.append(line)
        lastDumpAt = now
        dumpsThisOffer++
    }

    /**
     * Logs the delivery city + ZIP once per order, from the dropoff-nav screen.
     * Discriminates dropoff from pickup: the pickup leg shows "Pick up by …", so a
     * screen with a full address but no "Pick up by" is the dropoff leg. Only
     * city + ZIP are extracted — never the customer's exact street address.
     */
    /**
     * Tracks the "$X.XX this dash" running total (tips included as revealed) and
     * logs it when it changes. Also stashes it in [DashState] so the dash-end
     * summary can report the session's actual earnings.
     */
    private fun maybeLogDashTotal(texts: List<String>) {
        val idx = texts.indexOfFirst { it.contains("this dash", ignoreCase = true) }
        if (idx < 0) return
        var cents: Int? = null
        for (j in maxOf(0, idx - 2) until idx) {
            DOLLAR.find(texts[j])?.let { cents = centsOf(it.groupValues[1]) }
        }
        val c = cents ?: return
        DashState.totalCents = c
        if (c != lastLoggedDashTotal) {
            lastLoggedDashTotal = c
            EventLog.add(this, "DD this dash — $" + money(c))
        }
    }

    private fun centsOf(dollars: String): Int = Math.round(dollars.toDouble() * 100).toInt()

    /**
     * Reads the acceptance rate off the Ratings screen and stores it, so the
     * verdict can protect the decline budget. Anchors on an "Acceptance" label
     * then takes the nearest percentage (to avoid grabbing completion/rating).
     */
    private fun maybeCaptureAcceptance(texts: List<String>) {
        val idx = texts.indexOfFirst { it.contains("acceptance", ignoreCase = true) }
        if (idx < 0) return
        val pct = (idx until minOf(texts.size, idx + 3))
            .firstNotNullOfOrNull { PERCENT.find(texts[it])?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 0..100 } ?: return

        AcceptanceStore(this).setRate(pct)
        if (pct != lastLoggedAcceptance) {
            lastLoggedAcceptance = pct
            val target = SettingsStore(this).load().platinumTargetPercent
            val buffer = (pct - target).coerceAtLeast(0)
            EventLog.add(this, "DD acceptance rate — $pct% ($buffer declines to $target%)")
        }
    }

    /**
     * Captures a screenshot of the offer screen (map + pins) for offline analysis
     * of whether we can OCR the labels and detect pins. Uses the accessibility
     * screenshot API — no MediaProjection consent, on-device only. One per offer.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureOfferScreenshot(offerId: String, analyzeMap: Boolean) {
        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotIo,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        runCatching {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            result.hardwareBuffer.close()
                            if (bmp == null) return@runCatching
                            val dir = File(filesDir, "offer-screens").apply { mkdirs() }
                            val file = File(dir, "$offerId-${System.currentTimeMillis()}.jpg")
                            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            if (analyzeMap) {
                                MapReader.analyze(bmp) { r -> announceDestination(r); bmp.recycle() }
                            } else {
                                bmp.recycle()
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) { /* rate-limited or unsupported */ }
                },
            )
        }
    }

    /** Logs and (if voice is on) speaks the read destination. Silent when unsure. */
    private fun announceDestination(r: MapReader.Result) {
        val city = r.city ?: return
        val far = if (r.far) " · far" else ""
        EventLog.add(this, "DD destination — $city$far")
        if (SettingsStore(this).voiceEnabled()) {
            VoiceSpeaker.speak(this, "Delivering to $city" + if (r.far) ", far" else "")
        }
    }

    /**
     * On the earnings screen (has "Active time" + "Deliveries"), compares the
     * day's earn-by-order base pay against what Earn-by-Time would have paid.
     * Tips are equal both ways, so the only difference is base vs the hourly on
     * active time: advantage = rate × active_hrs − base × deliveries.
     */
    private fun maybeLogModeAdvisor(texts: List<String>) {
        val activeIdx = texts.indexOfFirst { it.equals("Active time", ignoreCase = true) }
        val delivIdx = texts.indexOfFirst { it.equals("Deliveries", ignoreCase = true) }
        if (activeIdx < 0 || delivIdx < 0) return

        val activeMin = parseHrMin(texts.getOrNull(activeIdx + 1)) ?: return
        val deliveries = texts.getOrNull(delivIdx + 1)?.trim()?.toIntOrNull()?.takeIf { it in 1..99 } ?: return
        if (activeMin == lastModeActiveMin) return
        lastModeActiveMin = activeMin

        val settings = SettingsStore(this).load()
        val hourly = settings.byTimeHourlyCents / 100.0
        val baseTotal = (settings.baseFloorCents / 100.0) * deliveries
        val hourlyTotal = hourly * (activeMin / 60.0)
        val advantage = hourlyTotal - baseTotal
        val mode = if (advantage >= 0) "earn-by-time" else "earn-by-order"
        val hrs = activeMin / 60
        val mins = activeMin % 60
        EventLog.add(
            this,
            "Mode check — %s ahead ~$%.0f  (%dh%02dm active · %d deliveries · base $%.0f vs $%.0f @ $%.2f/hr)"
                .format(mode, kotlin.math.abs(advantage), hrs, mins, deliveries, baseTotal, hourlyTotal, hourly),
        )
    }

    /** "2 hr 20 min" -> 140. */
    private fun parseHrMin(s: String?): Int? {
        s ?: return null
        val h = Regex("""(\d+)\s*hr""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(\d+)\s*min""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (h * 60 + m).takeIf { it > 0 }
    }

    private fun maybeLogDropoff(texts: List<String>) {
        if (texts.any { it.startsWith("Pick up by") }) return // pickup leg → skip
        val match = texts.firstNotNullOfOrNull { CITY_ZIP.find(it) } ?: return
        val city = match.groupValues[1].trim()
        val zip = match.groupValues[3]
        val label = "$city $zip"
        if (label == lastDropoff) return // already logged this delivery
        lastDropoff = label
        EventLog.add(this, "DD delivered to — $label")
    }

    /** Parses the offer screen and logs pay/miles/mode once per distinct offer. */
    private fun maybeLogPricedOffer(texts: List<String>) {
        val parsed = runCatching { OfferScreenParser.parse(texts) }.getOrNull() ?: return
        val id = parsed.offerId ?: return
        if (parsed.payCents == null && parsed.hourlyCents == null) return
        if (!emittedOfferIds.add(id)) return // already logged this offer
        lastDropoff = null // new order → allow its dropoff city/ZIP to log once

        // Screenshot the offer map; read the destination only for single offers
        // (batch maps have multiple dropoffs — too ambiguous to voice).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureOfferScreenshot(id, analyzeMap = parsed.stops == 1 && !parsed.isAddToRoute)
        }

        val settings = SettingsStore(this).load()
        val acceptanceRate = AcceptanceStore(this).rate()
        val verdict = runCatching {
            RulesEngine.evaluate(
                toOffer(parsed), settings, acceptanceRate,
                isAddToRoute = parsed.isAddToRoute,
                isEarnByTime = parsed.mode == EarnMode.TIME,
            )
        }.getOrNull()
        val estTipCents = estimatedTipCents(parsed, settings)

        val target = settings.platinumTargetPercent
        EventLog.add(this, formatPricedOffer(parsed, verdict, estTipCents, acceptanceRate, target))

        // Floating suggestion card (touch-transparent; passes taps to DoorDash).
        if (verdict != null && verdict.decision != Decision.INSUFFICIENT_DATA) {
            OverlayController.show(this, buildOverlay(parsed, verdict, estTipCents, acceptanceRate, target))
            if (SettingsStore(this).voiceEnabled()) {
                VoiceSpeaker.speak(this, spokenVerdict(parsed, verdict.decision))
            }
        }
    }

    /** "New order, estimated 5 dollars 50, 2.5 miles, 10 minutes. Recommend accept." */
    private fun spokenVerdict(p: ParsedOfferScreen, decision: Decision): String {
        val pay = estimatedEarningsCents(p)?.let {
            ", estimated ${spokenMoney(it)}" + if (p.mode == EarnMode.TIME) " plus tips" else ""
        }.orEmpty()
        val parts = buildList {
            p.miles?.let { add("$it miles") }
            p.minutes?.let { add("$it minutes") }
        }
        val trip = if (parts.isEmpty()) "" else " with ${parts.joinToString(" and ")} to complete"
        val rec = when (decision) {
            Decision.ACCEPT -> "Recommend accept."
            Decision.DECLINE -> "Recommend reject."
            Decision.MARGINAL -> "Marginal. Your call."
            else -> ""
        }
        return "New order$pay$trip. $rec"
    }

    private fun spokenMoney(cents: Int): String {
        val dollars = cents / 100
        val rem = cents % 100
        return if (rem == 0) "$dollars dollars" else "$dollars ${"%02d".format(rem)}"
    }

    /**
     * Estimated customer tip for earn-by-order offers only: the guaranteed total
     * already includes the (capped) tip, so tip ≈ total − promo − assumed base.
     * Null for earn-by-time (the shown rate excludes tips entirely).
     */
    private fun estimatedTipCents(p: ParsedOfferScreen, settings: com.ideliver.model.RuleSettings): Int? {
        if (p.mode != EarnMode.ORDER) return null
        val total = p.payCents ?: return null
        val base = settings.estimatedBaseCents(p.miles)
        return (total - (p.promoCents ?: 0) - base).coerceAtLeast(0)
    }

    private fun buildOverlay(
        p: ParsedOfferScreen,
        verdict: com.ideliver.model.Verdict,
        estTipCents: Int?,
        acceptanceRate: Int?,
        target: Int,
    ): OverlayContent {
        val estCents = estimatedEarningsCents(p)
        // Earn-by-order total already includes tips; only earn-by-time is "+tips".
        val estText = when {
            estCents == null -> "Est. —"
            p.mode == EarnMode.TIME -> "Est. $" + money(estCents) + " +tips"
            else -> "Est. $" + money(estCents)
        }
        val tipText = estTipCents?.let { "Tip ~$" + money(it) + " (est)" }

        val rateText = when {
            p.hourlyCents != null -> "$" + money(p.hourlyCents!!) + "/active hr"
            p.payCents != null -> {
                val miles = p.miles
                val perMi = if (miles != null && miles > 0) {
                    " · $" + money((p.payCents!! / miles).toInt()) + "/mi"
                } else ""
                "$" + money(p.payCents!!) + " guaranteed$perMi"
            }
            else -> ""
        }

        val distance = buildString {
            append(buildList {
                p.miles?.let { add("$it mi") }
                p.minutes?.let { add("$it min") }
            }.joinToString(" • "))
            when {
                p.isAddToRoute -> append("  · add to route")
                p.stops > 1 -> append("  · ${p.stops} stops")
            }
        }

        val reason = listOfNotNull(
            verdict.reasons.firstOrNull(),
            acceptanceNote(verdict.decision, p.mode, acceptanceRate, target),
        ).joinToString(" · ").ifEmpty { null }

        return OverlayContent(
            decision = verdict.decision,
            estEarningsText = estText,
            tipText = tipText,
            trueCostText = trueCostText(verdict),
            rateText = rateText,
            distanceText = distance,
            reasonText = reason,
        )
    }

    /**
     * Acceptance-rate reminder on any non-accept verdict: declining always costs
     * ~1% AR (100 offers to recover). Earn-by-time additionally allows only one
     * decline per hour before it forces a switch to Earn per Offer.
     */
    private fun acceptanceNote(decision: Decision, mode: EarnMode?, ar: Int?, target: Int): String? {
        if (decision != Decision.DECLINE && decision != Decision.MARGINAL) return null
        val core = when {
            ar == null -> "declining −1% AR"
            ar - target <= 0 -> "AR $ar% — at/below $target% floor, take it"
            else -> {
                val buffer = ar - target
                "AR $ar% · $buffer decline${if (buffer == 1) "" else "s"} to $target% floor"
            }
        }
        return if (mode == EarnMode.TIME) "$core · 1 decline/hr" else core
    }

    /** "all-in: $18/hr · $2.10/mi" — true-cost rates over delivery + empty return. */
    private fun trueCostText(verdict: com.ideliver.model.Verdict): String? {
        val perHour = verdict.dollarsPerHour?.let { "$" + "%.0f".format(it) + "/hr" }
        val perMile = verdict.dollarsPerMile?.let { "$" + "%.2f".format(it) + "/mi" }
        val parts = listOfNotNull(perHour, perMile)
        return if (parts.isEmpty()) null else "all-in: " + parts.joinToString(" · ")
    }

    /**
     * Estimated take for this offer. Earn-by-order = the guaranteed total.
     * Earn-by-time = hourly rate prorated over the offer's minutes. Tips are
     * hidden by DoorDash, so this is a floor (shown with "+tips").
     */
    private fun estimatedEarningsCents(p: ParsedOfferScreen): Int? = when {
        p.payCents != null -> p.payCents
        p.hourlyCents != null && p.minutes != null ->
            Math.round(p.hourlyCents!! * p.minutes!! / 60.0).toInt()
        else -> null
    }

    private fun toOffer(p: ParsedOfferScreen): Offer = Offer(
        platform = Platform.DOORDASH,
        source = CaptureSource.ACCESSIBILITY,
        // Guaranteed earnings for this offer (order total, or by-time rate × active
        // minutes) so the rules engine's true-cost math works for both modes.
        payCents = estimatedEarningsCents(p),
        miles = p.miles,
        estMinutes = p.minutes,
        storeName = p.store,
        stops = p.stops,
        seenAt = Instant.now(),
        confidence = 0.9f,
        rawText = "",
    )

    private fun formatPricedOffer(
        p: ParsedOfferScreen,
        verdict: com.ideliver.model.Verdict?,
        estTipCents: Int?,
        acceptanceRate: Int?,
        target: Int,
    ): String {
        val store = p.store ?: OfferSignal.lastStore ?: "offer"
        val hourlyCents = p.hourlyCents
        val miles = p.miles
        val parts = mutableListOf<String>()
        estimatedEarningsCents(p)?.let { parts.add("est $" + money(it)) }
        estTipCents?.let { parts.add("tip ~$" + money(it)) }
        when {
            p.payCents != null -> parts.add("$" + money(p.payCents!!))
            hourlyCents != null -> parts.add("$" + money(hourlyCents) + "/hr")
        }
        miles?.let { parts.add("$it mi") }
        p.minutes?.let { parts.add("$it min") }
        when {
            p.isAddToRoute -> parts.add("add to route")
            p.stops > 1 -> parts.add("${p.stops} stops")
        }
        parts.add(if (p.mode == EarnMode.TIME) "earn by time" else "earn by order")
        // True-cost rates over all legs (delivery + unpaid return).
        verdict?.dollarsPerHour?.let { parts.add("all-in $" + "%.0f".format(it) + "/hr") }
        verdict?.dollarsPerMile?.let { parts.add("$" + "%.2f".format(it) + "/mi") }

        val note = verdict?.let { acceptanceNote(it.decision, p.mode, acceptanceRate, target) }?.let { " · $it" }.orEmpty()
        val reason = verdict?.reasons?.firstOrNull()?.let { " ($it)" }.orEmpty()
        val rec = when (verdict?.decision) {
            Decision.ACCEPT -> " · ✅ ACCEPT"
            Decision.MARGINAL -> " · 🟡 MARGINAL$reason$note"
            Decision.DECLINE -> " · ❌ DECLINE$reason$note"
            else -> ""
        }
        return "DD offer — $store · ${parts.joinToString(" · ")}$rec"
    }

    private fun money(cents: Int): String = "%.2f".format(cents / 100.0)

    override fun onInterrupt() { /* no-op: nothing to interrupt in a read-only dumper */ }

    private companion object {
        const val MIN_DUMP_INTERVAL_MS = 1_000L
        const val MAX_DUMPS_PER_OFFER = 15
        const val DROPOFF_CHECK_INTERVAL_MS = 2_000L
        // "…, Bentonville AR 72712-6522, …" → city, state, 5-digit ZIP.
        val CITY_ZIP = Regex(""",\s*([A-Za-z][A-Za-z .]+?)\s+([A-Z]{2})\s+(\d{5})""")
        val DOLLAR = Regex("""\$(\d+\.\d{2})""")
        val PERCENT = Regex("""(\d{1,3})\s*%""")
    }
}
