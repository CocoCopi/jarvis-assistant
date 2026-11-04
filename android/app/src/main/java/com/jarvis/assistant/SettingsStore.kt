package com.jarvis.assistant

import android.content.Context
import org.json.JSONObject

data class JarvisSettings(
    val provider: String = "nvidia",
    val model: String = "nvidia/nemotron-3.5-lightning-30b-a3b",
    val baseUrl: String = "https://integrate.api.nvidia.com/v1",
    val apiKey: String = "",
    val system: String = "",
    val maxSteps: Int = 0,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val repo: String = BuildConfig.JARVIS_REPO,
    val wakeWord: String = "jarvis",
    val useWakeWord: Boolean = true,
    val autoStart: Boolean = false,
    val unsafe: Boolean = false,
    val autoUpdate: Boolean = true,
)

object SettingsStore {
    private const val PREFS = "jarvis_settings"

    fun load(context: Context): JarvisSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JarvisSettings(
            provider = p.getString("provider", "nvidia") ?: "nvidia",
            model = p.getString("model", "nvidia/nemotron-3.5-lightning-30b-a3b")
                ?: "nvidia/nemotron-3.5-lightning-30b-a3b",
            baseUrl = p.getString("base_url", "https://integrate.api.nvidia.com/v1")
                ?: "https://integrate.api.nvidia.com/v1",
            apiKey = p.getString("api_key", "") ?: "",
            system = p.getString("system", "") ?: "",
            maxSteps = p.getInt("max_steps", 0),
            temperature = p.getFloat("temperature", 0.7f).toDouble(),
            maxTokens = p.getInt("max_tokens", 4096),
            repo = p.getString("repo", BuildConfig.JARVIS_REPO) ?: BuildConfig.JARVIS_REPO,
            wakeWord = p.getString("wake_word", "jarvis") ?: "jarvis",
            useWakeWord = p.getBoolean("use_wake_word", true),
            autoStart = p.getBoolean("auto_start", false),
            unsafe = p.getBoolean("unsafe", false),
            autoUpdate = p.getBoolean("auto_update", true),
        )
    }

    fun save(context: Context, s: JarvisSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("provider", s.provider)
            .putString("model", s.model)
            .putString("base_url", s.baseUrl)
            .putString("api_key", s.apiKey)
            .putString("system", s.system)
            .putInt("max_steps", s.maxSteps)
            .putFloat("temperature", s.temperature.toFloat())
            .putInt("max_tokens", s.maxTokens)
            .putString("repo", s.repo)
            .putString("wake_word", s.wakeWord)
            .putBoolean("use_wake_word", s.useWakeWord)
            .putBoolean("auto_start", s.autoStart)
            .putBoolean("unsafe", s.unsafe)
            .putBoolean("auto_update", s.autoUpdate)
            .apply()
    }

    fun toJson(context: Context): String {
        val s = load(context)
        return JSONObject().apply {
            put("provider", s.provider)
            put("model", s.model)
            put("base_url", s.baseUrl)
            put("api_key", s.apiKey)
            put("system", s.system)
            put("max_steps", s.maxSteps)
            put("temperature", s.temperature)
            put("max_tokens", s.maxTokens)
            put("unsafe", s.unsafe)
            put("home", context.filesDir.absolutePath)
            put("skills_dir", context.filesDir.absolutePath + "/skills")
        }.toString()
    }
}
