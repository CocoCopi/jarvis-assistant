package com.jarvis.assistant

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
    private lateinit var useWakeWord: MaterialSwitch
    private lateinit var autoStart: MaterialSwitch
    private lateinit var autoUpdate: MaterialSwitch
    private lateinit var unsafe: MaterialSwitch

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
            setBackgroundColor(getColor(R.color.jarvis_bg))
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Settings"
            subtitle = "Connection, voice and updates"
            setTitleTextColor(getColor(R.color.jarvis_cyan))
            setSubtitleTextColor(getColor(R.color.jarvis_dim))
            setBackgroundColor(getColor(R.color.jarvis_bg))
        }
        root.addView(toolbar, matchWrap())
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
        val layout = TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = getColor(R.color.jarvis_surface)
            boxStrokeColor = getColor(R.color.jarvis_cyan_dark)
            setHintTextColor(getColor(R.color.jarvis_dim))
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            setPadding(0, dp(8), 0, 0)
        }
        val field = TextInputEditText(this).apply {
            setText(value)
            this.inputType = inputType
            this.minLines = minLines
            setTextColor(getColor(R.color.jarvis_text))
            setHintTextColor(getColor(R.color.jarvis_dim))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (minLines > 1) gravity = android.view.Gravity.TOP
        }
        layout.addView(field)
        parent.addView(layout, matchWrap())
        return field
    }

    private fun check(label: String, checked: Boolean): MaterialSwitch {
        return MaterialSwitch(this).apply {
            text = label
            isChecked = checked
            setTextColor(getColor(R.color.jarvis_text))
            setPadding(0, dp(6), 0, dp(6))
        }
    }

    private fun button(label: String, action: () -> Unit): Button {
        return MaterialButton(this).apply {
            text = label
            setOnClickListener { action() }
            setTextColor(getColor(R.color.jarvis_text))
            setBackgroundColor(getColor(R.color.jarvis_surface_2))
            textSize = 12f
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
