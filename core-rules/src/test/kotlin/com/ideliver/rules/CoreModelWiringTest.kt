package com.ideliver.rules

import com.ideliver.model.CaptureSource
import com.ideliver.model.Decision
import com.ideliver.model.Offer
import com.ideliver.model.Platform
import com.ideliver.model.Verdict
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Skeleton smoke test: proves core-rules can see the core-model types and that
 * the nullable Offer fields really are nullable. No rules logic exists yet.
 */
class CoreModelWiringTest {

    @Test
    fun `offer allows every economic field to be absent`() {
        val offer = Offer(
            platform = Platform.DOORDASH,
            source = CaptureSource.NOTIFICATION,
            payCents = null,
            miles = null,
            estMinutes = null,
            storeName = null,
            stops = null,
            seenAt = Instant.EPOCH,
            confidence = 0f,
            rawText = "raw",
        )

        assertNull(offer.payCents)
        assertEquals("raw", offer.rawText)
    }

    @Test
    fun `verdict carries a decision and reasons`() {
        val verdict = Verdict(
            decision = Decision.INSUFFICIENT_DATA,
            dollarsPerMile = null,
            dollarsPerHour = null,
            netAfterMileage = null,
            reasons = listOf("no pay in notification"),
        )

        assertEquals(Decision.INSUFFICIENT_DATA, verdict.decision)
        assertEquals(1, verdict.reasons.size)
    }
}
