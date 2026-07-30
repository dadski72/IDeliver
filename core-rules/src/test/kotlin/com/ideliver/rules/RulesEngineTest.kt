package com.ideliver.rules

import com.ideliver.model.CaptureSource
import com.ideliver.model.Decision
import com.ideliver.model.Offer
import com.ideliver.model.Platform
import com.ideliver.model.RuleSettings
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RulesEngineTest {

    private fun offer(payCents: Int?, miles: Double?, minutes: Int?, stops: Int? = 1) = Offer(
        platform = Platform.DOORDASH,
        source = CaptureSource.ACCESSIBILITY,
        payCents = payCents,
        miles = miles,
        estMinutes = minutes,
        storeName = null,
        stops = stops,
        seenAt = Instant.EPOCH,
        confidence = 1f,
        rawText = "",
    )

    @Test
    fun `no limits set accepts anything with data`() {
        val v = RulesEngine.evaluate(offer(500, 5.0, 20), RuleSettings())
        assertEquals(Decision.ACCEPT, v.decision)
    }

    @Test
    fun `missing miles and minutes is insufficient data`() {
        val v = RulesEngine.evaluate(offer(500, null, null), RuleSettings())
        assertEquals(Decision.INSUFFICIENT_DATA, v.decision)
    }

    @Test
    fun `over the hard radius limit declines`() {
        val v = RulesEngine.evaluate(offer(500, 12.0, 20), RuleSettings(radiusMiles = 10.0))
        assertEquals(Decision.DECLINE, v.decision)
    }

    @Test
    fun `near-miss on dollars per mile is marginal, not decline`() {
        // $6 over deadhead=1 → 5mi paid + 5mi return = 10mi driven → $0.60/mi.
        // min $0.65/mi: 0.60 is within 85% band (0.5525) so MARGINAL.
        val v = RulesEngine.evaluate(
            offer(600, 5.0, 20),
            RuleSettings(minDollarsPerMile = 0.65),
        )
        assertEquals(Decision.MARGINAL, v.decision)
    }

    @Test
    fun `single order over the time limit declines`() {
        val v = RulesEngine.evaluate(offer(870, 6.1, 31, stops = 1), RuleSettings(maxMinutes = 20))
        assertEquals(Decision.DECLINE, v.decision)
    }

    @Test
    fun `two-stop batch gets double the time budget`() {
        // 31 min, 2 stops → limit is 20×2 = 40 min, so it's within budget.
        val v = RulesEngine.evaluate(offer(870, 6.1, 31, stops = 2), RuleSettings(maxMinutes = 20))
        assertEquals(Decision.ACCEPT, v.decision)
    }

    @Test
    fun `earn-by-time ignores the time limit - longer is more pay`() {
        // 40-min offer, over a 20-min limit, but by-time pays for the time.
        val v = RulesEngine.evaluate(
            offer(850, 8.6, 40),
            RuleSettings(maxMinutes = 20),
            isEarnByTime = true,
        )
        assertEquals(Decision.ACCEPT, v.decision)
    }

    @Test
    fun `earn-by-order still declines over the time limit`() {
        val v = RulesEngine.evaluate(
            offer(850, 8.6, 40),
            RuleSettings(maxMinutes = 20),
            isEarnByTime = false,
        )
        assertEquals(Decision.DECLINE, v.decision)
    }

    @Test
    fun `add-to-route is exempt from the time limit`() {
        val v = RulesEngine.evaluate(
            offer(350, 4.4, 27, stops = 1),
            RuleSettings(maxMinutes = 20),
            isAddToRoute = true,
        )
        assertEquals(Decision.ACCEPT, v.decision)
    }

    @Test
    fun `earn-by-time far dropoff with long unpaid return is marginal`() {
        // Torchy's, 2026-07-29: 15.3 mi, 26 min active. Return ≈ 15.3/30*60 = 30.6 min,
        // active share 26/56.6 = 0.46 → between decline (0.45) and floor (0.60) → MARGINAL.
        val v = RulesEngine.evaluate(
            offer(553, 15.3, 26),
            RuleSettings(),
            isEarnByTime = true,
        )
        assertEquals(Decision.MARGINAL, v.decision)
    }

    @Test
    fun `earn-by-time nearby dropoff stays accept`() {
        // Dunkin', 2026-07-29: 4.9 mi, 16 min. Return ≈ 9.8 min, active share 0.62 → ACCEPT.
        val v = RulesEngine.evaluate(
            offer(340, 4.9, 16),
            RuleSettings(),
            isEarnByTime = true,
        )
        assertEquals(Decision.ACCEPT, v.decision)
    }

    @Test
    fun `earn-by-time extreme deadhead declines`() {
        // A 20-mi single drop, 20 min active. Return ≈ 40 min, active share 0.33 → DECLINE.
        val v = RulesEngine.evaluate(
            offer(425, 20.0, 20),
            RuleSettings(),
            isEarnByTime = true,
        )
        assertEquals(Decision.DECLINE, v.decision)
    }

    @Test
    fun `earn-by-time batch shares the return across stops and accepts`() {
        // Golden Corral + Handel's, 2026-07-29: 14.1 mi, 38 min, 2 stops. The empty
        // return is from the last of 2 dropoffs (~7 mi ≈ 14 min), active share 0.73 →
        // ACCEPT. The counter-check confirmed the near-identical Chipotle/Sweetgreen
        // batch paid ~$13/hr all-in, so this should not be penalized.
        val v = RulesEngine.evaluate(
            offer(808, 14.1, 38, stops = 2),
            RuleSettings(),
            isEarnByTime = true,
        )
        assertEquals(Decision.ACCEPT, v.decision)
    }

    @Test
    fun `earn-by-time same trip as a single (full return) would be marginal`() {
        // Same 14.1 mi / 38 min, but as one dropoff: full ~28 min return, share 0.58 →
        // MARGINAL. Contrast with the batch above — the stop count is what differs.
        val v = RulesEngine.evaluate(
            offer(808, 14.1, 38, stops = 1),
            RuleSettings(),
            isEarnByTime = true,
        )
        assertEquals(Decision.MARGINAL, v.decision)
    }

    @Test
    fun `earn-by-time add-to-route has no return and stays accept`() {
        // Taco Bell, 2026-07-29: add-to-route, 1.6 mi. No empty return → never trips the gate.
        val v = RulesEngine.evaluate(
            offer(744, 1.6, 35),
            RuleSettings(),
            isEarnByTime = true,
            isAddToRoute = true,
        )
        assertEquals(Decision.ACCEPT, v.decision)
    }

    @Test
    fun `clear miss on dollars per mile declines`() {
        // $2 → $0.20/mi over 10 driven, well under 85% of $0.65.
        val v = RulesEngine.evaluate(
            offer(200, 5.0, 20),
            RuleSettings(minDollarsPerMile = 0.65),
        )
        assertEquals(Decision.DECLINE, v.decision)
    }
}
