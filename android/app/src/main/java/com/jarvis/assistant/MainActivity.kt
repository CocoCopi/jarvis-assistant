package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.jarvis.assistant.engine.ChatResult
import com.jarvis.assistant.engine.CodeUpdater
import com.jarvis.assistant.engine.JarvisEngine
import com.jarvis.assistant.services.VoiceService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var send: Button
    private lateinit var talk: Button
    private lateinit var always: Button
    private var autoUpdateStarted = false

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
        send = findViewById(R.id.send)
        talk = findViewById(R.id.talk)
        always = findViewById(R.id.always)

        send.setOnClickListener { submit() }
        talk.setOnClickListener { pushToTalk() }
        always.setOnClickListener { toggleAlwaysListening() }
        findViewById<Button>(R.id.access).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.update).setOnClickListener { showUpdateDialog() }
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
            "Online. I can run this phone, control other apps, read notifications, " +
                "use the shell, install APKs, and write new skills for myself.",
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
        always.text = if (VoiceService.running) "STOP" else "ALWAYS"
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

    private fun runChat(text: String) {
        setBusy(true)
        JarvisEngine.chat(text) { result ->
            setBusy(false)
            showResult(result)
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
            always.text = "ALWAYS"
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
            append("System", "Always-listening started. Say \"${SettingsStore.load(this).wakeWord}\".")
        } catch (e: Throwable) {
            append("Error", "Could not start voice service: ${e.message}")
        }
    }

    private fun refreshStatus() {
        val settings = SettingsStore.load(this)
        val voice = if (VoiceService.running) {
            "voice:${VoiceService.state}"
        } else {
            "voice:off"
        }
        status.text = "${settings.provider}:${settings.model}  |  ${JarvisEngine.statusText()}  |  $voice"
    }

    fun appendTool(name: String, args: String) {
        runOnUiThread {
            val clean = args.replace("\n", " ").take(320)
            append("Tool", "$name $clean", dim = true)
        }
    }

    private fun append(who: String, message: String, dim: Boolean = false) {
        val color = when (who) {
            "You" -> ContextCompat.getColor(this, R.color.jarvis_cyan)
            "Jarvis" -> ContextCompat.getColor(this, R.color.jarvis_text)
            "Error" -> ContextCompat.getColor(this, R.color.jarvis_red)
            "Update" -> ContextCompat.getColor(this, R.color.jarvis_green)
            else -> ContextCompat.getColor(this, R.color.jarvis_dim)
        }
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            setCardBackgroundColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (dim) R.color.jarvis_surface else R.color.jarvis_card,
                ),
            )
            radius = dp(16).toFloat()
            setStrokeColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_card_stroke))
            strokeWidth = if (dim) 0 else dp(1)
            cardElevation = 0f
            setContentPadding(dp(12), dp(9), dp(12), dp(9))
            addView(
                TextView(this@MainActivity).apply {
                    text = if (who == "Tool") {
                        "[tool] ${message.take(4000)}"
                    } else {
                        "$who: ${message.take(20000)}"
                    }
                    setTextColor(
                        if (dim) ContextCompat.getColor(this@MainActivity, R.color.jarvis_dim) else color,
                    )
                    textSize = if (dim) 12f else 13.5f
                    setTextIsSelectable(true)
                    setLineSpacing(0f, 1.08f)
                },
            )
        }
        chat.addView(card)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
                JarvisEngine.reload { statusMessage ->
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
