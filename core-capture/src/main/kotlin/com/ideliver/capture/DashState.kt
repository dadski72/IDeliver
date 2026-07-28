package com.ideliver.capture

/**
 * The latest "$X.XX this dash" running total read off the DoorDash screen by the
 * accessibility service. Shared so the notification service can report it as the
 * session's actual earnings when the dash ends. Unlike the offer's "$/hr + tips"
 * guarantee, this figure already includes tips as they're revealed.
 */
object DashState {
    @Volatile
    var totalCents: Int? = null
}
