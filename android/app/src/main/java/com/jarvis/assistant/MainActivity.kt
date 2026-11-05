package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.engine.ChatResult
import com.jarvis.assistant.engine.CodeUpdater
import com.jarvis.assistant.engine.JarvisEngine
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.VoiceService
import com.jarvis.assistant.ui.JarvisOrbView
import com.jarvis.assistant.ui.JarvisOverlay
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
    private lateinit var always: MaterialButton
    private var autoUpdateStarted = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            Handler(Looper.getMainLooper()).postDelayed(this, 800L)
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
            append("System", "Microphone permission is required for voice mode.")
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

        send.setOnClickListener { submit() }
        talk.setOnClickListener { pushToTalk() }
        always.setOnClickListener { toggleAlwaysListening() }
        findViewById<TextView>(R.id.access).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        findViewById<TextView>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.update).setOnClickListener { showUpdateDialog() }
        findViewById<TextView>(R.id.chip_screen).setOnClickListener {
            runCommand("Read my screen and tell me what is visible.")
        }
        findViewById<TextView>(R.id.chip_notify).setOnClickListener {
            runCommand("List my notifications, then summarize the important ones.")
        }
        findViewById<TextView>(R.id.chip_capture).setOnClickListener {
            runCommand("Take an annotated screenshot and tell me what is on screen.")
        }
        findViewById<TextView>(R.id.chip_lock).setOnClickListener {
            runCommand("Lock the phone.")
        }
        findViewById<TextView>(R.id.chip_home).setOnClickListener {
            runCommand("Press home.")
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }

        append(
            "Jarvis",
            "Core online. Accessibility, notifications, gestures, vision, shell, apps, " +
                "settings and the island overlay are ready. Ask for anything.",
        )
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
        always.text = if (VoiceService.running) "STOP" else "ENGAGE"
        if (VoiceService.running) JarvisOverlay.ensure(this)
        Handler(Looper.getMainLooper()).removeCallbacks(ticker)
        Handler(Looper.getMainLooper()).post(ticker)
    }

    override fun onPause() {
        Handler(Looper.getMainLooper()).removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
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

    private fun runCommand(command: String) {
        append("You", command)
        runChat(command)
    }

    private fun runChat(text: String) {
        setBusy(true)
        JarvisOverlay.ensure(this)
        JarvisOverlay.update("thinking", "Thinking…")
        JarvisEngine.chat(text) { result ->
            setBusy(false)
            showResult(result)
            if (!VoiceService.running) JarvisOverlay.detach()
        }
    }

    private fun showResult(result: ChatResult) {
        if (result.ok) {
            if (result.reply.isNotBlank()) append("Jarvis", result.reply)
        } else {
            append("Error", result.error ?: "Unknown error")
        }
        refreshStatus()
    }

    private fun setBusy(busy: Boolean) {
        send.isEnabled = !busy
        talk.isEnabled = !busy
        input.isEnabled = !busy
    }

    private fun pushToTalk() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "No speech recognizer is installed.", Toast.LENGTH_LONG).show()
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
            always.text = "ENGAGE"
            append("System", "Always-listening stopped.")
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
            always.text = "STOP"
            JarvisOverlay.ensure(this)
            append("System", "Listening. Say \"${SettingsStore.load(this).wakeWord}\", " +
                "then keep talking without repeating it.")
            if (!android.provider.Settings.canDrawOverlays(this) &&
                JarvisAccessibilityService.instance == null
            ) {
                append(
                    "System",
                    "For the floating island, enable Accessibility or Display over other apps in ACCESS.",
                )
            }
        } catch (e: Throwable) {
            append("Error", "Could not start voice service: ${e.message}")
        }
    }

    private fun refreshStatus() {
        val settings = SettingsStore.load(this)
        val running = VoiceService.running
        val state = VoiceService.state
        val label = when {
            !running -> "CORE STANDBY"
            state.contains("wake", true) -> "AWAITING WAKE WORD"
            state.contains("listen", true) || state.contains("hear", true) -> "LISTENING"
            state.contains("think", true) || state.contains("process", true) -> "REASONING"
            state.contains("speak", true) -> "SPEAKING"
            state.contains("arm", true) -> "ENGAGED"
            else -> state.uppercase(Locale.US)
        }
        status.text = "$label  ·  ${settings.provider}:${settings.model.take(18)}"
        statusDot.alpha = if (running) 1f else 0.35f
        orb.setMode(if (running) state else "idle")
        heroTitle.text = when {
            !running -> "STANDBY"
            state.contains("wake", true) -> "WAKE WORD"
            state.contains("think", true) || state.contains("process", true) -> "THINKING"
            state.contains("speak", true) -> "SPEAKING"
            state.contains("listen", true) || state.contains("hear", true) -> "LISTENING"
            else -> "ENGAGED"
        }
        heroSub.text = if (running) {
            VoiceService.detail.ifBlank { "Listening continuously. No wake word needed now." }
        } else {
            "Say “Jarvis” or tap ENGAGE"
        }
        always.text = if (running) "STOP" else "ENGAGE"
    }

    fun appendTool(name: String, args: String) {
        runOnUiThread {
            val clean = args.replace("\n", " ").take(320)
            append("Tool", "$name $clean", dim = true)
            if (!VoiceService.running) {
                JarvisOverlay.ensure(this)
                JarvisOverlay.update("thinking", "Tool · ${name.replace('_', ' ')}")
            }
        }
    }

    private fun append(who: String, message: String, dim: Boolean = false) {
        if (who == "Tool" || who == "System" || who == "Update" || who == "Error") {
            appendSystem(who, message, dim)
            return
        }
        val isUser = who == "You"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleBackground(isUser)
            setPadding(dp(13), dp(9), dp(13), dp(10))
        }
        val name = TextView(this).apply {
            text = if (isUser) "YOU" else "J.A.R.V.I.S."
            letterSpacing = 0.14f
            setTextColor(
                if (isUser) ContextCompat.getColor(this@MainActivity, R.color.jarvis_cyan_dark)
                else ContextCompat.getColor(this@MainActivity, R.color.jarvis_cyan),
            )
            textSize = 8f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val body = TextView(this).apply {
            text = message.take(20000)
            setTextColor(
                if (dim) ContextCompat.getColor(this@MainActivity, R.color.jarvis_dim)
                else ContextCompat.getColor(this@MainActivity, R.color.jarvis_text),
            )
            textSize = 13.5f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.1f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        bubble.addView(name)
        bubble.addView(body)
        if (isUser) {
            row.addView(avatar("Y", true))
            row.addView(bubble, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(7) })
        } else {
            row.addView(avatar("J", false))
            row.addView(bubble, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(7) })
            row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        chat.addView(row)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendSystem(who: String, message: String, dim: Boolean) {
        val chip = TextView(this).apply {
            text = if (who == "Tool") "◈ ${message.take(4000)}" else "$who · ${message.take(20000)}"
            setTextColor(
                when (who) {
                    "Error" -> ContextCompat.getColor(this@MainActivity, R.color.jarvis_red)
                    "Update" -> ContextCompat.getColor(this@MainActivity, R.color.jarvis_green)
                    else -> ContextCompat.getColor(this@MainActivity, R.color.jarvis_dim)
                },
            )
            textSize = if (who == "Tool") 10f else 11f
            typeface = if (who == "Tool") Typeface.MONOSPACE else Typeface.DEFAULT
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(if (dim) 110 else 170, 12, 24, 38))
                setStroke(dp(1), Color.argb(90, 40, 92, 118))
            }
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(chip)
        chat.addView(row)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun avatar(letter: String, user: Boolean): TextView {
        return TextView(this).apply {
            text = letter
            gravity = Gravity.CENTER
            setTextColor(
                if (user) ContextCompat.getColor(this@MainActivity, R.color.black)
                else ContextCompat.getColor(this@MainActivity, R.color.jarvis_cyan),
            )
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (user) {
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_cyan))
                } else {
                    setColor(Color.rgb(11, 25, 38))
                    setStroke(dp(1), ContextCompat.getColor(this@MainActivity, R.color.jarvis_cyan_dark))
                }
            }
            val size = dp(26)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM
                topMargin = dp(2)
            }
        }
    }

    private fun bubbleBackground(user: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = if (user) {
                floatArrayOf(
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(4).toFloat(), dp(4).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                )
            } else {
                floatArrayOf(
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(4).toFloat(), dp(4).toFloat(),
                )
            }
            if (user) {
                setColor(Color.rgb(8, 42, 56))
                setStroke(dp(1), Color.rgb(26, 93, 116))
            } else {
                setColor(Color.rgb(12, 23, 36))
                setStroke(dp(1), Color.rgb(27, 56, 78))
            }
        }
    }

    private fun maybeAutoUpdate() {
        if (autoUpdateStarted) return
        autoUpdateStarted = true
        val settings = SettingsStore.load(this)
        if (!settings.autoUpdate || settings.repo.isBlank()) return
        Thread {
            val message = CodeUpdater.checkForCode(applicationContext, settings.repo)
            if (message.contains("Downloaded")) {
                runOnUiThread { append("Update", message) }
                JarvisEngine.reload {
                    runOnUiThread {
                        append("Update", "Applied ${JarvisEngine.statusText()}")
                        refreshStatus()
                    }
                }
            }
        }.start()
    }

    private fun showUpdateDialog() {
        val settings = SettingsStore.load(this)
        if (settings.repo.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("GitHub repo missing")
                .setMessage(
                    "Set owner/repo in Settings. The CI build normally bakes this in automatically.",
                )
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Update Jarvis")
            .setMessage("Repo: ${settings.repo}")
            .setPositiveButton("Code") { _, _ -> updateCode(settings.repo) }
            .setNeutralButton("APK") { _, _ -> updateApk(settings.repo) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCode(repo: String) {
        append("Update", "Checking $repo for new code…")
        Thread {
            val message = CodeUpdater.checkForCode(applicationContext, repo)
            runOnUiThread { append("Update", message) }
            if (message.contains("Downloaded")) {
                JarvisEngine.reload {
                    runOnUiThread {
                        append("Update", "Applied ${JarvisEngine.statusText()}")
                        refreshStatus()
                    }
                }
            }
        }.start()
    }

    private fun updateApk(repo: String) {
        append("Update", "Checking $repo for a new APK…")
        Thread {
            val message = CodeUpdater.installApkUpdate(applicationContext, repo)
            runOnUiThread { append("Update", message) }
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
