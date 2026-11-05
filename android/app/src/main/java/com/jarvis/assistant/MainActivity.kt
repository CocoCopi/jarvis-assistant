package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.jarvis.assistant.engine.ChatResult
import com.jarvis.assistant.engine.CodeUpdater
import com.jarvis.assistant.engine.JarvisEngine
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.VoiceService
import com.jarvis.assistant.ui.JarvisOrbView
import com.jarvis.assistant.ui.JarvisOverlay
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var orb: JarvisOrbView
    private lateinit var send: ImageButton
    private lateinit var talk: ImageButton
    private lateinit var always: TextView
    private lateinit var menu: ImageButton

    private var autoUpdateStarted = false
    private var activityLine: String? = null
    private var pendingRow: View? = null
    private var pendingBody: TextView? = null
    private var thinkingFrame = 0

    private val main = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, 650L)
        }
    }

    private val thinking = object : Runnable {
        override fun run() {
            val body = pendingBody ?: return
            thinkingFrame += 1
            body.text = "·".repeat((thinkingFrame % 3) + 1) + "  "
            body.alpha = 0.45f + 0.2f * (thinkingFrame % 2)
            main.postDelayed(this, 320L)
        }
    }

    private val talkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                append("You", text)
                runChat(text)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startVoice()
        } else {
            appendSystem("Error", "Microphone permission is required for voice.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        current = this

        chat = findViewById(R.id.chat)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.status_dot)
        heroTitle = findViewById(R.id.hero_title)
        heroSub = findViewById(R.id.hero_sub)
        orb = findViewById(R.id.orb)
        send = findViewById(R.id.send)
        talk = findViewById(R.id.talk)
        always = findViewById(R.id.always)
        menu = findViewById(R.id.menu)

        send.setOnClickListener { submit() }
        talk.setOnClickListener { pushToTalk() }
        always.setOnClickListener { toggleAlwaysListening() }
        menu.setOnClickListener { showMenu(it) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }

        append("Jarvis", greeting())
        refreshStatus()

        JarvisEngine.init(applicationContext) {
            refreshStatus()
            maybeAutoUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        current = this
        refreshStatus()
        if (VoiceService.running) JarvisOverlay.ensure(this)
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (current === this) current = null
        super.onDestroy()
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        append("You", text)
        runChat(text)
    }

    private fun runChat(text: String) {
        setBusy(true)
        activityLine = "Consulting the core"
        showThinking()
        JarvisOverlay.ensure(this)
        JarvisOverlay.update("thinking", "One moment, sir…")
        val started = SystemClock.elapsedRealtime()
        JarvisEngine.chat(text) { result ->
            setBusy(false)
            activityLine = null
            clearThinking()
            showResult(result, SystemClock.elapsedRealtime() - started)
            if (!VoiceService.running) JarvisOverlay.detach()
        }
    }

    private fun showResult(result: ChatResult, elapsedMs: Long) {
        if (result.ok) {
            if (result.reply.isNotBlank()) append("Jarvis", result.reply)
        } else {
            appendSystem("Error", result.error ?: "Unknown error")
        }
        if (elapsedMs < 600 && result.ok) heroSub.text = "At your service, sir."
        refreshStatus()
    }

    private fun setBusy(busy: Boolean) {
        send.isEnabled = !busy
        talk.isEnabled = !busy
        send.alpha = if (busy) 0.35f else 1f
        talk.alpha = if (busy) 0.35f else 1f
    }

    private fun pushToTalk() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "No speech recogniser is installed.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Yes, sir?")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            talkLauncher.launch(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleAlwaysListening() {
        if (VoiceService.running) {
            VoiceService.stop(this)
            always.text = "◉  LISTEN"
            appendSystem("System", "Standing by.")
            return
        }
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (mic != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
            )
            return
        }
        startVoice()
    }

    private fun startVoice() {
        try {
            VoiceService.start(this)
            always.text = "◼  STOP"
            JarvisOverlay.ensure(this)
            if (!android.provider.Settings.canDrawOverlays(this) &&
                JarvisAccessibilityService.instance == null
            ) {
                appendSystem(
                    "System",
                    "For the floating island, enable Accessibility or overlays in the menu.",
                )
            }
        } catch (e: Throwable) {
            appendSystem("Error", "Could not start voice: ${e.message}")
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Access")
        popup.menu.add(0, 2, 1, "Settings")
        popup.menu.add(0, 3, 2, "Sync code")
        popup.menu.add(0, 4, 3, "Install APK")
        popup.menu.add(0, 5, 4, "Clear conversation")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, PermissionsActivity::class.java))
                2 -> startActivity(Intent(this, SettingsActivity::class.java))
                3 -> syncCode()
                4 -> installApk()
                5 -> clearConversation()
            }
            true
        }
        popup.show()
    }

    private fun clearConversation() {
        JarvisEngine.reset { }
        chat.removeAllViews()
        append("Jarvis", greeting())
    }

    private fun refreshStatus() {
        val running = VoiceService.running
        val state = VoiceService.state
        val label = when {
            !running -> "STANDBY"
            state.contains("wake", true) -> "AWAITING WAKE WORD"
            state.contains("listen", true) || state.contains("hear", true) -> "LISTENING"
            state.contains("think", true) || state.contains("process", true) -> "REASONING"
            state.contains("speak", true) -> "SPEAKING"
            state.contains("arm", true) -> "ENGAGED"
            else -> state.uppercase(Locale.US)
        }
        status.text = label
        statusDot.alpha = if (running) 1f else 0.4f
        orb.setMode(if (running) state else "idle")
        heroTitle.text = when {
            !running -> "STANDBY"
            state.contains("wake", true) -> "WAKE WORD"
            state.contains("think", true) || state.contains("process", true) -> "REASONING"
            state.contains("speak", true) -> "SPEAKING"
            state.contains("listen", true) || state.contains("hear", true) -> "LISTENING"
            else -> "ENGAGED"
        }
        heroSub.text = activityLine ?: if (running) {
            VoiceService.detail.ifBlank { "Listening, sir." }
        } else {
            "At your service, sir."
        }
        always.text = if (running) "◼  STOP" else "◉  LISTEN"
    }

    fun appendTool(name: String, args: String) {
        runOnUiThread {
            val clean = name.replace('_', ' ')
            activityLine = "Using $clean"
            JarvisOverlay.ensure(this)
            JarvisOverlay.update("thinking", "Using $clean")
        }
    }

    private fun showThinking() {
        clearThinking()
        thinkingFrame = 0
        val row = messageShell("J.A.R.V.I.S.", "", user = false, thinking = true)
        val body = row.getChildAt(1) as TextView
        body.text = "·  "
        pendingRow = row
        pendingBody = body
        row.alpha = 0f
        chat.addView(row)
        row.animate().alpha(1f).setDuration(160L).start()
        scrollDown()
        main.post(thinking)
    }

    private fun clearThinking() {
        main.removeCallbacks(thinking)
        pendingRow?.let { chat.removeView(it) }
        pendingRow = null
        pendingBody = null
    }

    private fun append(who: String, message: String) {
        if (who == "Tool" || who == "System" || who == "Update" || who == "Error") {
            appendSystem(who, message)
            return
        }
        val row = messageShell(
            if (who == "You") "YOU" else "J.A.R.V.I.S.",
            message,
            user = who == "You",
            thinking = false,
        )
        row.alpha = 0f
        chat.addView(row)
        row.animate().alpha(1f).setDuration(200L).start()
        scrollDown()
    }

    private fun messageShell(
        label: String,
        message: String,
        user: Boolean,
        thinking: Boolean,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(13), 0, dp(13))
        }
        val marker = TextView(this).apply {
            text = label
            letterSpacing = 0.22f
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (user) R.color.jarvis_dim else R.color.jarvis_cyan_dark,
                ),
            )
            textSize = 8f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val body = TextView(this).apply {
            text = message.take(20000)
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (user) R.color.jarvis_cyan_dark else R.color.jarvis_text,
                ),
            )
            textSize = if (user) 14f else 15f
            setTextIsSelectable(!thinking)
            setLineSpacing(dp(3).toFloat(), 1.12f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
            if (user) typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        row.addView(marker)
        row.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        return row
    }

    private fun appendSystem(who: String, message: String) {
        val text = TextView(this).apply {
            this.text = message.take(20000)
            gravity = Gravity.CENTER
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    when (who) {
                        "Error" -> R.color.jarvis_red
                        "Update" -> R.color.jarvis_green
                        else -> R.color.jarvis_dim
                    },
                ),
            )
            textSize = 10f
            letterSpacing = 0.04f
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(text)
        chat.addView(row)
        scrollDown()
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when {
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part, sir."
    }

    private fun syncCode() {
        val settings = SettingsStore.load(this)
        if (settings.repo.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("No repository")
                .setMessage("Set owner/repo in Settings first.")
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        activityLine = "Synchronising source"
        appendSystem("Update", "Checking ${settings.repo}…")
        Thread {
            val message = CodeUpdater.checkForCode(applicationContext, settings.repo)
            runOnUiThread { appendSystem("Update", message) }
            if (message.contains("Downloaded")) {
                JarvisEngine.reload {
                    runOnUiThread {
                        activityLine = null
                        appendSystem("Update", "Applied ${JarvisEngine.statusText()}")
                        refreshStatus()
                    }
                }
            } else {
                runOnUiThread { activityLine = null }
            }
        }.start()
    }

    private fun installApk() {
        val repo = SettingsStore.load(this).repo
        if (repo.isBlank()) {
            appendSystem("Error", "Set owner/repo in Settings first.")
            return
        }
        activityLine = "Checking for a new build"
        Thread {
            val message = CodeUpdater.installApkUpdate(applicationContext, repo)
            runOnUiThread {
                activityLine = null
                appendSystem("Update", message)
            }
        }.start()
    }

    private fun maybeAutoUpdate() {
        if (autoUpdateStarted) return
        autoUpdateStarted = true
        val settings = SettingsStore.load(this)
        if (!settings.autoUpdate || settings.repo.isBlank()) return
        Thread {
            val message = CodeUpdater.checkForCode(applicationContext, settings.repo)
            if (message.contains("Downloaded")) {
                runOnUiThread { appendSystem("Update", message) }
                JarvisEngine.reload {
                    runOnUiThread {
                        appendSystem("Update", "Applied ${JarvisEngine.statusText()}")
                        refreshStatus()
                    }
                }
            }
        }.start()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    companion object {
        @Volatile
        var current: MainActivity? = null
            private set
    }
}
