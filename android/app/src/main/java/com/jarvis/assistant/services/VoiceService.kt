package com.jarvis.assistant.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.SettingsStore
import com.jarvis.assistant.TtsManager
import com.jarvis.assistant.engine.JarvisEngine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class VoiceService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var processing = false
    private var armed = false
    private var armedAt = 0L
    private var settings = SettingsStore.load(JarvisApp.instance)
    private var lastText = "Starting…"
    private var ignoreRecognizerErrors = false

    private val watchdog = Runnable {
        if (!running || processing) return@Runnable
        destroyRecognizer()
        listening = false
        startListening()
    }

    private var lastPartial = ""
    private val partialArm = Runnable {
        if (!running || processing || armed) return@Runnable
        if (commandAfterWakeWord(lastPartial)?.isBlank() == true) {
            armAndPrompt()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        state = "starting"
        JarvisEngine.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        settings = SettingsStore.load(this)
        startForegroundCompat(buildNotification("Starting…"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("Microphone permission is required")
            stopSelf()
            return START_NOT_STICKY
        }
        startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        state = "stopped"
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        TtsManager.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startListening() {
        if (!running || processing || listening) return
        main.removeCallbacks(partialArm)
        if (armed && SystemClock.elapsedRealtime() - armedAt > WAKE_WINDOW_MS) {
            armed = false
        }
        destroyRecognizer()
        ignoreRecognizerErrors = false
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        speech.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        try {
            speech.startListening(intent)
            listening = true
            state = if (settings.useWakeWord && !armed) "waiting for wake word" else "listening"
            updateNotification(
                if (settings.useWakeWord && !armed) {
                    "Say \"${settings.wakeWord}\""
                } else {
                    "Listening…"
                },
            )
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, LISTEN_TIMEOUT_MS)
        } catch (e: Throwable) {
            listening = false
            state = "retrying"
            retry()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            state = if (settings.useWakeWord && !armed) "waiting for wake word" else "listening"
            updateNotification(
                if (settings.useWakeWord && !armed) "Waiting for wake word" else "Listening…",
            )
        }

        override fun onBeginningOfSpeech() {
            state = "hearing speech"
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
            state = "processing speech"
        }

        override fun onError(error: Int) {
            listening = false
            main.removeCallbacks(watchdog)
            destroyRecognizer()
            if (!running) return
            if (ignoreRecognizerErrors) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                updateNotification("Microphone permission is required")
                stopSelf()
                return
            }
            if (error == SpeechRecognizer.ERROR_CLIENT && processing) return
            retry()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            main.removeCallbacks(watchdog)
            main.removeCallbacks(partialArm)
            destroyRecognizer()
            val text = firstResult(results)
            if (text.isBlank()) {
                if (armed) {
                    speakThen("I'm listening.") { if (running) startListening() }
                } else if (commandAfterWakeWord(lastPartial)?.isBlank() == true) {
                    armAndPrompt()
                } else {
                    retry()
                }
                return
            }
            handleTranscript(text, final = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isBlank()) return
            updateNotification(text.take(90))
            if (processing) return
            lastPartial = text
            main.removeCallbacks(partialArm)
            if (settings.useWakeWord && !armed) {
                val command = commandAfterWakeWord(text)
                if (command != null && command.isBlank()) {
                    main.postDelayed(partialArm, 800)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun handleTranscript(text: String, final: Boolean) {
        if (!running) return
        if (settings.useWakeWord && !armed) {
            val command = commandAfterWakeWord(text) ?: run {
                if (final) retry()
                return
            }
            if (command.isBlank()) {
                armAndPrompt()
                return
            }
            armed = false
            process(command)
            return
        }
        if (!final) return
        armed = false
        process(text.trim())
    }

    private fun armAndPrompt() {
        if (processing) return
        main.removeCallbacks(partialArm)
        armed = true
        armedAt = SystemClock.elapsedRealtime()
        ignoreRecognizerErrors = true
        state = "armed"
        destroyRecognizer()
        main.removeCallbacks(watchdog)
        updateNotification("Wake word heard. Listening…")
        speakThen("Yes, sir?") { if (running) startListening() }
    }

    private fun commandAfterWakeWord(text: String): String? {
        val words = normalize(text).split(' ').filter { it.isNotBlank() }
        val wake = normalize(settings.wakeWord).split(' ').filter { it.isNotBlank() }
        if (wake.isEmpty() || words.size < wake.size) return null
        for (index in 0..(words.size - wake.size)) {
            var match = true
            for (offset in wake.indices) {
                if (words[index + offset] != wake[offset]) {
                    match = false
                    break
                }
            }
            if (match) {
                return words.drop(index + wake.size).joinToString(" ")
            }
        }
        return null
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun process(command: String) {
        if (command.isBlank()) {
            retry()
            return
        }
        processing = true
        state = "thinking"
        armed = false
        TtsManager.stop()
        updateNotification("Thinking…")
        JarvisEngine.chat(command) { result ->
            if (!running) {
                processing = false
                return@chat
            }
            val response = if (result.ok) {
                result.reply.ifBlank { "Done, sir." }
            } else {
                "I ran into a problem: ${result.error ?: "unknown error"}"
            }
            state = "speaking"
            updateNotification("Speaking…")
            speakThen(response) {
                processing = false
                if (running) {
                    main.postDelayed({ startListening() }, 300)
                }
            }
        }
    }

    private fun speakThen(text: String, next: () -> Unit) {
        val finished = AtomicBoolean(false)
        val action = {
            if (finished.compareAndSet(false, true)) {
                main.postDelayed(next, 180)
            }
        }
        TtsManager.speak(text, action)
        val timeout = (TTS_FALLBACK_MS + text.length * 70L).coerceAtMost(20_000L)
        main.postDelayed({ action() }, timeout)
    }

    private fun retry() {
        if (!running) return
        state = "retrying"
        main.removeCallbacks(watchdog)
        main.postDelayed({
            if (running && !processing) startListening()
        }, 650)
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
        listening = false
    }

    private fun buildNotification(text: String): Notification {
        lastText = text
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, JarvisApp.VOICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!running) return
        lastText = text
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.jarvis.assistant.VOICE_START"
        const val ACTION_STOP = "com.jarvis.assistant.VOICE_STOP"
        private const val NOTIF_ID = 4242
        private const val LISTEN_TIMEOUT_MS = 18_000L
        private const val WAKE_WINDOW_MS = 15_000L
        private const val TTS_FALLBACK_MS = 3_500L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var state: String = "stopped"
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VoiceService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
