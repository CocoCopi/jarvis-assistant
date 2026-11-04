package com.jarvis.assistant.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.jarvis.assistant.SettingsStore
import org.json.JSONObject
import java.util.concurrent.Executors

data class ToolLog(val name: String, val args: String)

data class ChatResult(
    val ok: Boolean,
    val reply: String,
    val error: String?,
    val tools: List<ToolLog>,
)

object JarvisEngine {
    private const val TAG = "JarvisEngine"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-engine").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var bootstrap: PyObject? = null
    @Volatile private var contextRef: Context? = null
    @Volatile private var started = false
    @Volatile var lastStatus: String = "{\"source\":\"none\"}"
        private set

    fun init(context: Context, callback: ((String) -> Unit)? = null) {
        contextRef = context.applicationContext
        executor.execute {
            val status = ensureStarted(context.applicationContext)
            main.post { callback?.invoke(status) }
        }
    }

    fun chat(text: String, callback: (ChatResult) -> Unit) {
        val context = contextRef
        executor.execute {
            val result = try {
                if (context == null) throw IllegalStateException("Jarvis is not initialised")
                if (!started) ensureStarted(context)
                val module = bootstrap ?: throw IllegalStateException("Python bootstrap unavailable")
                val raw = module.callAttr("chat", text).toString()
                parseChat(raw)
            } catch (e: Throwable) {
                Log.e(TAG, "chat failed", e)
                ChatResult(false, "", e.message ?: e.javaClass.name, emptyList())
            }
            main.post { callback(result) }
        }
    }

    fun reload(callback: ((String) -> Unit)? = null) {
        val context = contextRef ?: return
        executor.execute {
            started = false
            val status = ensureStarted(context)
            main.post { callback?.invoke(status) }
        }
    }

    fun reset(callback: ((String) -> Unit)? = null) {
        executor.execute {
            val message = try {
                bootstrap?.callAttr("reset")?.toString() ?: "Engine not started"
            } catch (e: Throwable) {
                "Reset failed: ${e.message}"
            }
            main.post { callback?.invoke(message) }
        }
    }

    fun tools(callback: (String) -> Unit) {
        executor.execute {
            val value = try {
                bootstrap?.callAttr("tools_json")?.toString() ?: "[]"
            } catch (e: Throwable) {
                "[]"
            }
            main.post { callback(value) }
        }
    }

    fun statusText(): String {
        return try {
            val json = JSONObject(lastStatus)
            val setup = if (json.isNull("setup_error")) "" else json.optString("setup_error")
            if (json.optBoolean("ok", false) && setup.isBlank()) {
                "code ${json.optString("version")} (${json.optString("source")})"
            } else {
                "engine error: " + (setup.ifBlank { json.optString("error") }).take(160)
            }
        } catch (e: Throwable) {
            lastStatus
        }
    }

    private fun ensureStarted(context: Context): String {
        if (started && bootstrap != null) return lastStatus
        val status = try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            val python = Python.getInstance()
            bootstrap = python.getModule("jarvis_android.bootstrap")
            val result = bootstrap!!.callAttr(
                "start",
                SettingsStore.toJson(context),
                context.filesDir.absolutePath,
            ).toString()
            lastStatus = result
            started = try {
                JSONObject(result).optBoolean("ok", false)
            } catch (e: Throwable) {
                false
            }
            result
        } catch (e: Throwable) {
            Log.e(TAG, "engine start failed", e)
            lastStatus = JSONObject().apply {
                put("ok", false)
                put("source", "none")
                put("error", e.message ?: e.javaClass.name)
            }.toString()
            started = false
            lastStatus
        }
        return status
    }

    private fun parseChat(raw: String): ChatResult {
        return try {
            val json = JSONObject(raw)
            val tools = mutableListOf<ToolLog>()
            val array = json.optJSONArray("tools")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    tools.add(ToolLog(item.optString("name"), item.optString("args")))
                }
            }
            ChatResult(
                ok = json.optBoolean("ok", false),
                reply = json.optString("reply", ""),
                error = if (json.has("error")) json.optString("error") else null,
                tools = tools,
            )
        } catch (e: Throwable) {
            ChatResult(true, raw, null, emptyList())
        }
    }
}
