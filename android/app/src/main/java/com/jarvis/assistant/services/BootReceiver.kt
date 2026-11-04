package com.jarvis.assistant.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.SettingsStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val settings = SettingsStore.load(context)
        if (!settings.autoStart || !settings.useWakeWord) return
        try {
            VoiceService.start(context)
        } catch (_: Throwable) {
        }
    }
}
