package com.ideliver.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * On-device text-to-speech for hands-free offer verdicts. No network — the
 * point is to hear the recommendation without looking at the phone in a mount.
 * App-process singleton; init is async so the first offer (minutes later) is
 * ready. A new offer flushes any stale utterance.
 *
 * Audio is tagged as navigation guidance and, before each utterance, requests
 * transient audio focus (ducking) — so it stays audible over music/nav and, when
 * projecting to Android Auto, is routed like turn-by-turn guidance to the car
 * rather than swallowed. Focus is abandoned when the utterance finishes.
 *
 * Logs under tag "IDeliverVoice" so a silent phone can be diagnosed with
 * `adb logcat -s IDeliverVoice` — init status, voice-data availability, focus
 * grants, and every speak call are reported.
 */
object VoiceSpeaker {

    private const val TAG = "IDeliverVoice"

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    // If an utterance is requested before init finishes, hold the latest one and
    // speak it the moment we're ready — don't silently drop the first offer.
    @Volatile
    private var pending: String? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    fun init(context: Context) {
        if (tts != null) return
        Log.i(TAG, "init: creating TextToSpeech")
        val app = context.applicationContext
        audioManager = app.getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        tts = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "init failed: status=$status (no usable TTS engine)")
                return@TextToSpeech
            }
            val engine = tts
            val lang = engine?.setLanguage(Locale.US)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Engine is up but the en-US voice isn't downloaded — this is the
                // usual "installed but silent" cause. Fall back to the default locale.
                Log.w(TAG, "en-US voice unavailable (result=$lang); trying default locale")
                engine?.setLanguage(Locale.getDefault())
            }
            engine?.setAudioAttributes(attributes)
            // Release the ducking hold as soon as each line finishes speaking.
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = abandonFocus()
                @Deprecated("deprecated in API 21") override fun onError(utteranceId: String?) = abandonFocus()
                override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
            })
            ready = true
            Log.i(TAG, "init ok (lang=$lang); pending=${pending != null}")
            pending?.let { pending = null; utter(it) }
        }
    }

    fun speak(context: Context, text: String) {
        init(context)
        Log.i(TAG, "speak(ready=$ready): $text")
        if (!ready) { pending = text; return }
        utter(text)
    }

    /** Speaks a fixed phrase so the user can verify audio without a live offer. */
    fun test(context: Context) = speak(context, "IDeliver voice test. New order, estimated eight dollars. Recommend accept.")

    private fun utter(text: String) {
        // Duck other audio (music/nav) so the line is heard, in the car included.
        val focus = focusRequest?.let { audioManager?.requestAudioFocus(it) }
        Log.i(TAG, "audio focus request = $focus")
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ideliver-offer")
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak returned $result (not queued)")
            abandonFocus()
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }
}
