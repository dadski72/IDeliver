package com.ideliver.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ideliver.model.Decision

/**
 * What the suggestion card shows, pre-formatted by the caller.
 * [estEarningsText] is the headline (estimated take for this offer); [rateText]
 * is the smaller pay-rate context; [distanceText] is the emphasized miles/time.
 */
data class OverlayContent(
    val decision: Decision,
    val estEarningsText: String,
    val tipText: String?,
    val trueCostText: String?,
    val rateText: String,
    val distanceText: String,
    val reasonText: String?,
)

/**
 * The floating suggestion card. Deliberately minimal and, above all, *safe*:
 *
 *  - It is touch-transparent (FLAG_NOT_TOUCHABLE) — every tap passes straight
 *    through to DoorDash, so it can never swallow an Accept/Decline tap.
 *  - It sits centred, clear of the top Decline and bottom Accept buttons.
 *  - It removes itself after a few seconds (or when [dismiss] is called).
 *
 * The app never taps anything; this only shows a recommendation the driver acts
 * on themselves. A plain WindowManager view, not Compose, per the module's brief.
 */
object OverlayController {

    private const val AUTO_DISMISS_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    private var current: View? = null

    fun show(context: Context, content: OverlayContent) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        handler.post {
            removeView(app)
            val wm = app.getSystemService(WindowManager::class.java) ?: return@post
            val view = buildCard(app, content)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                y = -dp(app, 80) // bias upward, further from the bottom Accept button
            }
            runCatching { wm.addView(view, params) }.onSuccess { current = view }
            handler.postDelayed({ removeView(app) }, AUTO_DISMISS_MS)
        }
    }

    fun dismiss(context: Context) {
        val app = context.applicationContext
        handler.post { removeView(app) }
    }

    private fun removeView(context: Context) {
        handler.removeCallbacksAndMessages(null)
        val view = current ?: return
        val wm = context.getSystemService(WindowManager::class.java)
        runCatching { wm?.removeView(view) }
        current = null
    }

    private fun buildCard(context: Context, content: OverlayContent): View {
        val accent = when (content.decision) {
            Decision.ACCEPT -> Color.parseColor("#34C759")
            Decision.DECLINE -> Color.parseColor("#FF453A")
            else -> Color.parseColor("#FFB020") // MARGINAL / review
        }
        val verdictText = when (content.decision) {
            Decision.ACCEPT -> "ACCEPT"
            Decision.DECLINE -> "REJECT"
            Decision.MARGINAL -> "MARGINAL"
            else -> "REVIEW"
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 20)
            setPadding(pad, dp(context, 16), pad, dp(context, 16))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(Color.parseColor("#F2101014"))
                setStroke(dp(context, 2), accent)
            }
        }

        // Verdict badge.
        card.addView(
            TextView(context).apply {
                text = verdictText
                setTextColor(accent)
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        // Headline: estimated earnings for this offer.
        card.addView(
            TextView(context).apply {
                text = content.estEarningsText
                setTextColor(Color.WHITE)
                textSize = 38f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        // Estimated tip (earn-by-order only).
        content.tipText?.takeIf { it.isNotBlank() }?.let { tip ->
            card.addView(
                TextView(context).apply {
                    text = tip
                    setTextColor(Color.parseColor("#34C759"))
                    textSize = 20f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                },
            )
        }
        // Smaller: the pay rate context (e.g. "$12.75/active hr").
        card.addView(
            TextView(context).apply {
                text = content.rateText
                setTextColor(Color.parseColor("#B0B0B8"))
                textSize = 16f
            },
        )
        // Emphasized: distance and time.
        card.addView(
            TextView(context).apply {
                text = content.distanceText
                setTextColor(Color.parseColor("#E5E5EA"))
                textSize = 30f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(context, 4), 0, 0)
            },
        )
        // True-cost rates over all legs (delivery + unpaid return).
        content.trueCostText?.takeIf { it.isNotBlank() }?.let { tc ->
            card.addView(
                TextView(context).apply {
                    text = tc
                    setTextColor(accent)
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(context, 4), 0, 0)
                },
            )
        }
        content.reasonText?.takeIf { it.isNotBlank() }?.let { reason ->
            card.addView(
                TextView(context).apply {
                    text = reason
                    setTextColor(Color.parseColor("#B0B0B8"))
                    textSize = 14f
                    setPadding(0, dp(context, 4), 0, 0)
                },
            )
        }
        return card
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
