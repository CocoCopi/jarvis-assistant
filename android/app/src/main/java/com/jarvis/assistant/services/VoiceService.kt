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

class VoiceService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var processing = false
    private var armed = false
    private var settings = SettingsStore.load(JarvisApp.instance)
    private var lastText = "Starting…"

    override fun onCreate() {
        super.onCreate()
        running = true
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
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        TtsManager.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startListening() {
        if (!running || processing || listening) return
        destroyRecognizer()
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            speech.startListening(intent)
            listening = true
            val hint = if (settings.useWakeWord && !armed) {
                "Say \"${settings.wakeWord}\""
            } else {
                "Listening…"
            }
            updateNotification(hint)
        } catch (e: Throwable) {
            listening = false
            retry()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            updateNotification(if (settings.useWakeWord && !armed) "Waiting for wake word" else "Listening…")
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            destroyRecognizer()
            if (!running) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                updateNotification("Microphone permission is required")
                stopSelf()
                return
            }
            retry()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            destroyRecognizer()
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isBlank()) {
                retry()
                return
            }
            handleTranscript(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotBlank()) updateNotification(text.take(80))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleTranscript(text: String) {
        if (!running) return
        if (settings.useWakeWord && !armed) {
            val index = text.lowercase(Locale.US).indexOf(settings.wakeWord.lowercase(Locale.US))
            if (index < 0) {
                retry()
                return
            }
            val rest = text.substring(index + settings.wakeWord.length)
                .trim(' ', ',', '.', '!', '?', ':')
            if (rest.isBlank()) {
                armed = true
                TtsManager.speak("Yes, sir?") { startListening() }
                return
            }
            armed = false
            process(rest)
            return
        }
        armed = false
        process(text)
    }

    private fun process(command: String) {
        processing = true
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
            updateNotification("Speaking…")
            TtsManager.speak(response) {
                processing = false
                if (running) main.postDelayed({ startListening() }, 350)
            }
        }
    }

    private fun retry() {
        if (!running) return
        main.postDelayed({ startListening() }, 700)
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

        @Volatile
        var running: Boolean = false
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
