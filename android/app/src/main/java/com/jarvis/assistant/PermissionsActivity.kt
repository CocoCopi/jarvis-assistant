package com.jarvis.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.jarvis.assistant.admin.JarvisDeviceAdminReceiver
import com.jarvis.assistant.bridge.DeviceBridge

class PermissionsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.jarvis_bg))
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(
            MaterialToolbar(this).apply {
                title = "Access control"
                subtitle = "Grant Jarvis the whole phone"
                setTitleTextColor(getColor(R.color.jarvis_cyan))
                setSubtitleTextColor(getColor(R.color.jarvis_dim))
                setBackgroundColor(getColor(R.color.jarvis_bg))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.jarvis_dim))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(
            MaterialCardView(this).apply {
                cardBackgroundColor = getColor(R.color.jarvis_surface)
                radius = dp(18).toFloat()
                strokeColor = getColor(R.color.jarvis_card_stroke)
                strokeWidth = dp(1)
                cardElevation = 0f
                addView(status)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); bottomMargin = dp(8) },
        )

        root.addView(
            button("GRANT RUNTIME PERMISSIONS") {
                runtimePermissions.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.READ_CALL_LOG,
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
        )
        root.addView(button("ACCESSIBILITY SERVICE") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("NOTIFICATION LISTENER") {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("ALL FILES ACCESS") {
            openAllFiles()
        })
        root.addView(button("DISPLAY OVER OTHER APPS") {
            open(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        })
        root.addView(button("INSTALL UNKNOWN APPS") {
            open(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
        })
        root.addView(button("BATTERY UNRESTRICTED") {
            open(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        })
        root.addView(button("USAGE ACCESS") {
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(button("WRITE SYSTEM SETTINGS") {
            open(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        })
        root.addView(button("DEVICE ADMIN") { requestDeviceAdmin() })
        root.addView(button("DEVICE OWNER / ROOT HELP") { showDeviceOwnerHelp() })

        root.addView(
            TextView(this).apply {
                text = "For full ADB-level power without root, install Shizuku and grant it " +
                    "wireless-debugging access. For device-owner power (silent policy control, " +
                    "lock, wipe), use the adb command in the help dialog."
                textSize = 12f
                setTextColor(getColor(R.color.jarvis_dim))
                setPadding(0, dp(20), 0, 0)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val accessibility = isAccessibilityEnabled()
        val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val files = Environment.isExternalStorageManager()
        val overlay = Settings.canDrawOverlays(this)
        val admin = DeviceBridge.deviceAdmin("status")
        status.text = buildString {
            append("accessibility: ").append(if (accessibility) "on" else "off").append('\n')
            append("notification listener: ").append(if (listener) "on" else "off").append('\n')
            append("all files: ").append(if (files) "on" else "off").append('\n')
            append("overlay: ").append(if (overlay) "on" else "off").append('\n')
            append(admin).append('\n')
            append("root: ").append(DeviceBridge.isRooted())
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val component = "$packageName/${com.jarvis.assistant.services.JarvisAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(component, ignoreCase = true) } ||
            enabled.contains("JarvisAccessibilityService")
    }

    private fun requestDeviceAdmin() {
        val component = ComponentName(this, JarvisDeviceAdminReceiver::class.java)
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Jarvis uses device admin for lock, camera control and policy actions.",
            )
        open(intent)
    }

    private fun showDeviceOwnerHelp() {
        val text = """
            Device owner (run once over adb, phone must have no accounts):
            adb shell dpm set-device-owner $packageName/.admin.JarvisDeviceAdminReceiver

            Root shell:
            Root the phone, install Magisk, then grant su to Jarvis when asked.

            Wireless ADB / Shizuku:
            Install Shizuku from the Play Store, start it with wireless debugging, then grant Jarvis access.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Maximum power")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openAllFiles() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            open(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, "This device has no screen for that: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun button(label: String, action: () -> Unit): Button {
        return MaterialButton(this).apply {
            text = label
            setOnClickListener { action() }
            setTextColor(getColor(R.color.jarvis_cyan))
            setBackgroundColor(getColor(R.color.jarvis_surface))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
}
