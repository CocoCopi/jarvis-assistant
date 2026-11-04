package com.jarvis.assistant

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.engine.CodeUpdater
import com.jarvis.assistant.engine.JarvisEngine

class SettingsActivity : AppCompatActivity() {

    private lateinit var provider: Spinner
    private lateinit var model: EditText
    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var system: EditText
    private lateinit var repo: EditText
    private lateinit var wakeWord: EditText
    private lateinit var maxSteps: EditText
    private lateinit var temperature: EditText
    private lateinit var maxTokens: EditText
    private lateinit var useWakeWord: CheckBox
    private lateinit var autoStart: CheckBox
    private lateinit var autoUpdate: CheckBox
    private lateinit var unsafe: CheckBox

    private val providers = arrayOf("nvidia", "openai", "anthropic", "ollama")
    private val defaults = mapOf(
        "nvidia" to ("nvidia/nemotron-3.5-lightning-30b-a3b" to "https://integrate.api.nvidia.com/v1"),
        "openai" to ("gpt-4o-mini" to "https://api.openai.com/v1"),
        "anthropic" to ("claude-sonnet-4-5" to "https://api.anthropic.com"),
        "ollama" to ("llama3.1" to "http://localhost:11434"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(title("Provider"))
        provider = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                providers,
            )
            setSelection(providers.indexOf(settings.provider).coerceAtLeast(0))
        }
        root.addView(provider, matchWrap())

        model = addField(root, "Model", settings.model)
        baseUrl = addField(root, "Base URL", settings.baseUrl)
        apiKey = addField(root, "API key", settings.apiKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        repo = addField(root, "GitHub repo (owner/repo)", settings.repo)
        wakeWord = addField(root, "Wake word", settings.wakeWord)
        maxSteps = addField(root, "Max tool steps (0 = unlimited)", settings.maxSteps.toString(), InputType.TYPE_CLASS_NUMBER)
        temperature = addField(root, "Temperature", settings.temperature.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        maxTokens = addField(root, "Max output tokens", settings.maxTokens.toString(), InputType.TYPE_CLASS_NUMBER)
        system = addField(root, "Extra system prompt", settings.system, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, minLines = 3)

        useWakeWord = check("Require wake word", settings.useWakeWord)
        autoStart = check("Start always-listening on boot", settings.autoStart)
        autoUpdate = check("Auto-apply GitHub code updates", settings.autoUpdate)
        unsafe = check("Unsafe: skip approval prompts", settings.unsafe)
        root.addView(useWakeWord)
        root.addView(autoStart)
        root.addView(autoUpdate)
        root.addView(unsafe)

        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var first = true
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (first) {
                    first = false
                    return
                }
                val chosen = providers[position]
                defaults[chosen]?.let { (defaultModel, defaultBase) ->
                    model.setText(defaultModel)
                    baseUrl.setText(defaultBase)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        root.addView(button("SAVE AND RELOAD") { save() }, matchWrap())
        root.addView(button("INSTALL APK FROM LATEST RELEASE") { installApk() }, matchWrap())
        root.addView(button("RESET DOWNLOADED CODE") { resetCode() }, matchWrap())

        root.addView(
            TextView(this).apply {
                text = "The API key stays in this app's private storage. Code and skills update " +
                    "from GitHub without uninstalling the app. APK signature changes still require " +
                    "one uninstall, so add the signing secrets before the first install."
                textSize = 12f
                setTextColor(getColor(R.color.jarvis_dim))
                setPadding(0, dp(16), 0, 0)
            },
        )
    }

    private fun save() {
        val selectedProvider = providers[provider.selectedItemPosition]
        val settings = JarvisSettings(
            provider = selectedProvider,
            model = model.text.toString().trim(),
            baseUrl = baseUrl.text.toString().trim(),
            apiKey = apiKey.text.toString().trim(),
            system = system.text.toString().trim(),
            maxSteps = maxSteps.text.toString().toIntOrNull() ?: 0,
            temperature = temperature.text.toString().toDoubleOrNull() ?: 0.7,
            maxTokens = maxTokens.text.toString().toIntOrNull() ?: 4096,
            repo = repo.text.toString().trim(),
            wakeWord = wakeWord.text.toString().trim().ifBlank { "jarvis" },
            useWakeWord = useWakeWord.isChecked,
            autoStart = autoStart.isChecked,
            unsafe = unsafe.isChecked,
            autoUpdate = autoUpdate.isChecked,
        )
        SettingsStore.save(this, settings)
        JarvisEngine.init(applicationContext)
        JarvisEngine.reload {
            runOnUiThread {
                Toast.makeText(this, "Saved. ${JarvisEngine.statusText()}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun installApk() {
        val repo = SettingsStore.load(this).repo
        Toast.makeText(this, "Checking $repo…", Toast.LENGTH_SHORT).show()
        Thread {
            val message = CodeUpdater.installApkUpdate(applicationContext, repo)
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("APK update")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun resetCode() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reset downloaded code?")
            .setMessage("This removes downloaded Python code. The bundled snapshot is used on next start.")
            .setPositiveButton("Reset") { _, _ ->
                Toast.makeText(this, CodeUpdater.reset(this), Toast.LENGTH_LONG).show()
                JarvisEngine.reload {
                    runOnUiThread {
                        Toast.makeText(this, JarvisEngine.statusText(), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addField(
        parent: LinearLayout,
        label: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        minLines: Int = 1,
    ): EditText {
        parent.addView(
            TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(getColor(R.color.jarvis_dim))
                setPadding(0, dp(14), 0, dp(3))
            },
        )
        val field = EditText(this).apply {
            setText(value)
            this.inputType = inputType
            this.minLines = minLines
            setTextColor(getColor(R.color.jarvis_text))
            setHintTextColor(getColor(R.color.jarvis_dim))
            setBackgroundColor(getColor(R.color.jarvis_surface))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            if (minLines > 1) gravity = android.view.Gravity.TOP
        }
        parent.addView(field, matchWrap())
        return field
    }

    private fun check(label: String, checked: Boolean): CheckBox {
        return CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(getColor(R.color.jarvis_text))
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { action() }
            setBackgroundColor(getColor(R.color.jarvis_cyan_dark))
            setTextColor(getColor(R.color.jarvis_text))
        }
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(getColor(R.color.jarvis_cyan))
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
}
