package com.ideliver.capture

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * On-device text-to-speech for hands-free offer verdicts. No network — the
 * point is to hear the recommendation without looking at the phone in a mount.
 * App-process singleton; init is async so the first offer (minutes later) is
 * ready. A new offer flushes any stale utterance.
 */
object VoiceSpeaker {

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready = true
            }
        }
    }

    fun speak(context: Context, text: String) {
        init(context)
        val engine = tts ?: return
        if (!ready) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ideliver-offer")
    }
}
