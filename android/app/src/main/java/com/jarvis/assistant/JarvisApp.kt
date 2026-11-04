package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        TtsManager.init(this)
        createChannels(this)
    }

    companion object {
        lateinit var instance: JarvisApp
            private set

        const val VOICE_CHANNEL = "jarvis_voice"
        const val ALERT_CHANNEL = "jarvis_alerts"

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    VOICE_CHANNEL,
                    "Jarvis voice",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Persistent notification while Jarvis listens" },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL,
                    "Jarvis alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}
