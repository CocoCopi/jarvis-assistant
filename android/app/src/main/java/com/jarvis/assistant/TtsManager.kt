package com.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object TtsManager {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var pendingDone: (() -> Unit)? = null
    private var currentDone: (() -> Unit)? = null

    fun init(context: Context) {
        if (engine != null) return
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                engine?.language = Locale.UK
                engine?.setSpeechRate(0.97f)
                engine?.setPitch(1.0f)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post {
                            val callback = currentDone
                            currentDone = null
                            callback?.invoke()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post {
                            val callback = currentDone
                            currentDone = null
                            callback?.invoke()
                        }
                    }
                })
                pending?.let { text ->
                    val callback = pendingDone
                    pending = null
                    pendingDone = null
                    speak(text, callback)
                }
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        main.post { speakNow(text, onDone) }
    }

    private fun speakNow(text: String, onDone: (() -> Unit)?) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }
        if (!ready || engine == null) {
            pending = clean
            pendingDone = onDone
            return
        }
        currentDone = onDone
        engine?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.nanoTime()}")
    }

    fun stop() {
        main.post {
            currentDone = null
            pendingDone = null
            engine?.stop()
        }
    }
}
