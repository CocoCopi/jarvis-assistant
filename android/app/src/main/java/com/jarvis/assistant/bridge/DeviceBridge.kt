package com.jarvis.assistant.bridge

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.TtsManager
import com.jarvis.assistant.admin.JarvisDeviceAdminReceiver
import com.jarvis.assistant.engine.CodeUpdater
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.JarvisNotificationService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object DeviceBridge {
    private val io = Executors.newCachedThreadPool()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var torchOn = false

    private fun context(): Context = JarvisApp.instance.applicationContext

    private fun service(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

    @JvmStatic
    fun requestApproval(name: String, detail: String): Boolean {
        val activity = MainActivity.current ?: return false
        val latch = CountDownLatch(1)
        var allowed = false
        activity.runOnUiThread {
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Allow ${name.replace('_', ' ')}?")
                    .setMessage(detail.ifBlank { "Jarvis wants to use $name." }.take(2500))
                    .setPositiveButton("Allow") { _, _ ->
                        allowed = true
                        latch.countDown()
                    }
                    .setNegativeButton("Deny") { _, _ -> latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            } catch (e: Throwable) {
                latch.countDown()
            }
        }
        return try {
            latch.await(180, TimeUnit.SECONDS)
            allowed
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    @JvmStatic
    fun logTool(name: String, args: String) {
        MainActivity.current?.appendTool(name, args)
    }

    @JvmStatic
    fun speak(text: String): String {
        TtsManager.speak(text)
        return "Speaking."
    }

    @JvmStatic
    fun deviceInfo(): String = guarded {
        val ctx = context()
        val battery = ctx.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
        )
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val usedGb = String.format(Locale.US, "%.1f", (total - free) / 1_073_741_824.0)
        val totalGb = String.format(Locale.US, "%.1f", total / 1_073_741_824.0)
        val metrics = ctx.resources.displayMetrics
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) on " +
            "${Build.MANUFACTURER} ${Build.MODEL}. Battery $level% " +
            (if (charging) "charging" else "discharging") +
            ". Storage $usedGb/$totalGb GB. Screen ${metrics.widthPixels}x${metrics.heightPixels}. " +
            "IP ${ipAddress()}. Root: ${rootStatus()}."
    }

    @JvmStatic
    fun shell(command: String, root: Boolean): String = guarded {
        if (command.isBlank()) return@guarded "No command supplied."
        if (root && !hasRoot()) {
            return@guarded "Root requested, but no su binary was found. " +
                "Use non-root mode, or root the device and grant Jarvis su access."
        }
        val args = if (root) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val reader = io.submit<String> {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@guarded "Command timed out after 120 seconds."
        }
        val output = try {
            reader.get(5, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            ""
        }
        val code = process.exitValue()
        val text = output.ifBlank { "(no output)" }.take(20_000)
        "Exit code $code. $text"
    }

    @JvmStatic
    fun uiDump(): String = screenState(false, false)

    @JvmStatic
    fun screenState(includeScreenshot: Boolean, annotate: Boolean): String = guarded {
        service()?.screenState(includeScreenshot, annotate)
            ?: "Accessibility service is not enabled. Open Access and enable Jarvis."
    }

    @JvmStatic
    fun findNodes(query: String, by: String, exact: Boolean, includeOffscreen: Boolean): String = guarded {
        service()?.findNodes(query, by, exact, includeOffscreen)
            ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun nodeDetails(id: String): String = guarded {
        service()?.nodeDetails(id) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun clickNode(id: String, long: Boolean): String = guarded {
        service()?.clickNode(id, long) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun tapNode(id: String): String = guarded {
        service()?.tapNode(id) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun scrollNode(id: String, direction: String): String = guarded {
        service()?.scrollNode(id, direction) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun scrollToNode(id: String): String = guarded {
        service()?.scrollToNode(id) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun nodeAction(id: String, action: String): String = guarded {
        service()?.nodeAction(id, action) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun clickText(text: String, exact: Boolean): String = guarded {
        service()?.clickText(text, exact)
            ?: "Accessibility service is not enabled. Open Access and enable Jarvis."
    }

    @JvmStatic
    fun longPress(x: Int, y: Int, duration: Int): String = guarded {
        service()?.longPress(x, y, duration) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun doubleTap(x: Int, y: Int): String = guarded {
        service()?.doubleTap(x, y) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun scrollDirection(direction: String, amountPercent: Int): String = guarded {
        service()?.scrollDirection(direction, amountPercent) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun pinch(centerX: Int, centerY: Int, scale: Float, duration: Int): String = guarded {
        service()?.pinch(centerX, centerY, scale, duration) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun customGesture(pathsJson: String): String = guarded {
        service()?.customGesture(pathsJson) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun typeTextEx(text: String, mode: String, nodeId: String): String = guarded {
        service()?.typeText(text, mode, nodeId) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun pressKeyEx(name: String, nodeId: String): String = guarded {
        service()?.pressKey(name, nodeId) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun waitForNode(query: String, by: String, exact: Boolean, timeout: Int): String = guarded {
        service()?.waitForNode(query, by, exact, timeout) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun waitForIdle(timeout: Int): String = guarded {
        service()?.waitForIdle(timeout) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun currentApp(): String = guarded {
        service()?.currentApp() ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun tap(x: Int, y: Int): String = guarded {
        service()?.tap(x, y) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): String = guarded {
        service()?.swipe(x1, y1, x2, y2, duration)
            ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun typeText(text: String): String = guarded {
        service()?.typeText(text) ?: "Accessibility service is not enabled."
    }

    @JvmStatic
    fun key(name: String): String = guarded {
        val ctx = context()
        val key = name.trim().lowercase(Locale.US)
        val audio = ctx.getSystemService(AudioManager::class.java)
        when (key) {
            "volume_up" -> {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI,
                )
                "Volume up."
            }
            "volume_down" -> {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI,
                )
                "Volume down."
            }
            "mute" -> {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_SHOW_UI,
                )
                "Muted."
            }
            "media_play_pause", "play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next", "media_next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous", "media_previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "stop", "media_stop" -> mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
            else -> service()?.pressKey(key) ?: "Accessibility service is not enabled."
        }
    }

    @JvmStatic
    fun screenshot(path: String): String = guarded {
        service()?.screenshot(path)
            ?: "Accessibility service is not enabled. Open Access and enable Jarvis."
    }

    @JvmStatic
    fun screenshotAnnotated(path: String): String = guarded {
        service()?.screenshot(path, true)
            ?: "Accessibility service is not enabled. Open Access and enable Jarvis."
    }

    @JvmStatic
    fun notifications(): String = guarded {
        JarvisNotificationService.instance?.dump()
            ?: "Notification listener is not enabled. Open Access and enable Jarvis notifications."
    }

    @JvmStatic
    fun notificationAction(action: String, key: String, index: Int, minutes: Int, text: String): String = guarded {
        val service = JarvisNotificationService.instance
            ?: return@guarded "Notification listener is not enabled. Open Access and enable Jarvis notifications."
        when (action.lowercase(Locale.US)) {
            "open" -> service.open(key)
            "dismiss", "clear" -> service.dismiss(key)
            "snooze" -> service.snooze(key, minutes)
            "action", "press" -> service.action(key, index)
            "reply" -> service.reply(key, text)
            "details" -> service.details(key)
            else -> "Unknown notification action: $action"
        }
    }

    @JvmStatic
    fun apps(filter: String): String = guarded {
        val ctx = context()
        val pm = ctx.packageManager
        val result = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .filter { (pkg, label) ->
                filter.isBlank() || pkg.contains(filter, true) || label.contains(filter, true)
            }
            .sortedBy { it.second.lowercase(Locale.US) }
            .take(300)
            .joinToString("\n") { (pkg, label) -> "$pkg - $label" }
        result.ifBlank { "No apps matched $filter." }
    }

    @JvmStatic
    fun launchApp(packageName: String): String = guarded {
        val ctx = context()
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@guarded "No launchable app with package $packageName."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        "Launched $packageName."
    }

    @JvmStatic
    fun stopApp(packageName: String): String = guarded {
        val am = context().getSystemService(ActivityManager::class.java)
        am.killBackgroundProcesses(packageName)
        "Requested stop for $packageName."
    }

    @JvmStatic
    fun open(target: String): String = guarded {
        val ctx = context()
        val clean = target.trim()
        if (clean.isBlank()) return@guarded "Nothing to open."
        val intent = when {
            clean.contains("://") -> Intent(Intent.ACTION_VIEW, Uri.parse(clean))
            clean == "settings" -> Intent(Settings.ACTION_SETTINGS)
            clean == "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            clean == "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            clean == "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            clean == "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            clean == "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            else -> ctx.packageManager.getLaunchIntentForPackage(clean)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse(clean))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        "Opened $clean."
    }

    @JvmStatic
    fun sendIntent(action: String, uri: String, packageName: String, extrasJson: String): String = guarded {
        val ctx = context()
        val intent = Intent(action.ifBlank { Intent.ACTION_VIEW })
        if (uri.isNotBlank()) intent.data = Uri.parse(uri)
        if (packageName.isNotBlank()) intent.setPackage(packageName)
        if (extrasJson.isNotBlank()) {
            val extras = JSONObject(extrasJson)
            for (key in extras.keys()) {
                intent.putExtra(key, extras.optString(key))
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        "Intent sent: ${action.ifBlank { "VIEW" }} ${uri}."
    }

    @JvmStatic
    fun clipboardGet(): String = guarded {
        val ctx = context()
        val manager = ctx.getSystemService(android.content.ClipboardManager::class.java)
        manager.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
    }

    @JvmStatic
    fun clipboardSet(text: String): String = guarded {
        val ctx = context()
        val manager = ctx.getSystemService(android.content.ClipboardManager::class.java)
        manager.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis", text))
        "Copied ${text.length} characters."
    }

    @JvmStatic
    fun toast(message: String): String = guarded {
        val ctx = context()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
        "Toast shown."
    }

    @JvmStatic
    fun sendSms(number: String, message: String): String = guarded {
        val ctx = context()
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@guarded "SMS permission is not granted. Open Access and grant it."
        }
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = manager.divideMessage(message)
        manager.sendMultipartTextMessage(number, null, parts, null, null)
        "SMS sent to $number."
    }

    @JvmStatic
    fun call(number: String): String = guarded {
        val ctx = context()
        val canCall = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL)
        intent.data = Uri.parse("tel:$number")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        if (canCall) "Calling $number." else "Dialer opened for $number (call permission not granted)."
    }

    @JvmStatic
    fun contacts(query: String): String = guarded {
        val ctx = context()
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@guarded "Contacts permission is not granted. Open Access and grant it."
        }
        val resolver = ctx.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = if (query.isBlank()) {
            null
        } else {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        }
        val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        val lines = mutableListOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && lines.size < 80) {
                lines.add("${cursor.getString(0)} - ${cursor.getString(1)}")
            }
        }
        lines.joinToString("\n").ifBlank { "No contacts matched $query." }
    }

    @JvmStatic
    fun location(): String = guarded {
        val ctx = context()
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@guarded "Location permission is not granted. Open Access and grant it."
        }
        val manager = ctx.getSystemService(LocationManager::class.java)
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        for (provider in providers) {
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (e: Throwable) {
                null
            }
            if (location != null) {
                return@guarded "Location ${location.latitude}, ${location.longitude} " +
                    "accuracy ${location.accuracy} m via $provider."
            }
        }
        "No last known location yet."
    }

    @JvmStatic
    fun fileRead(path: String, maxChars: Int): String = guarded {
        val file = resolve(path)
        if (!file.isFile) return@guarded "File not found: ${file.absolutePath}"
        val limit = maxChars.coerceIn(100, 200_000)
        val text = file.inputStream().bufferedReader().use { reader ->
            val buffer = CharArray(limit)
            val count = reader.read(buffer)
            if (count <= 0) "" else String(buffer, 0, count)
        }
        text
    }

    @JvmStatic
    fun fileWrite(path: String, content: String): String = guarded {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        "Wrote ${content.length} characters to ${file.absolutePath}."
    }

    @JvmStatic
    fun fileList(path: String): String = guarded {
        val directory = resolve(path)
        if (!directory.isDirectory) return@guarded "Not a directory: ${directory.absolutePath}"
        directory.listFiles()
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.take(500)
            ?.joinToString("\n") { if (it.isDirectory) "${it.name}/" else it.name }
            ?.ifBlank { "(empty)" }
            ?: "(cannot list)"
    }

    @JvmStatic
    fun fileDelete(path: String): String = guarded {
        val file = resolve(path)
        if (!file.exists()) return@guarded "Path does not exist: ${file.absolutePath}"
        if (file.deleteRecursively()) "Deleted ${file.absolutePath}." else "Could not delete ${file.absolutePath}."
    }

    @JvmStatic
    fun installApk(path: String): String = guarded {
        val ctx = context()
        val file = resolve(path)
        if (!file.isFile) return@guarded "APK not found: ${file.absolutePath}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(settings)
            return@guarded "Allow Jarvis to install unknown apps, then try again."
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(intent)
        "Installer opened for ${file.name}."
    }

    @JvmStatic
    fun uninstall(packageName: String): String = guarded {
        val ctx = context()
        val intent = Intent(
            Intent.ACTION_DELETE,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        "Uninstall prompt opened for $packageName."
    }

    @JvmStatic
    fun appInfo(packageName: String): String = guarded {
        val ctx = context()
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        "Opened app info for $packageName."
    }

    @JvmStatic
    fun deviceAdmin(action: String): String = guarded {
        val ctx = context()
        val admin = ComponentName(ctx, JarvisDeviceAdminReceiver::class.java)
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val active = dpm.isAdminActive(admin)
        val owner = dpm.isDeviceOwnerApp(ctx.packageName)
        if (action == "status") {
            return@guarded "Device admin: $active. Device owner: $owner."
        }
        if (!active) {
            return@guarded "Device admin is not active. Open Access and enable Jarvis device admin."
        }
        when (action) {
            "lock" -> {
                dpm.lockNow()
                "Locked."
            }
            "wipe" -> {
                if (!owner) return@guarded "Wipe requires device-owner mode. See Access for the adb command."
                dpm.wipeData(0)
                "Wipe started."
            }
            "camera_off" -> {
                dpm.setCameraDisabled(admin, true)
                "Camera disabled."
            }
            "camera_on" -> {
                dpm.setCameraDisabled(admin, false)
                "Camera enabled."
            }
            else -> "Unknown admin action: $action"
        }
    }

    @JvmStatic
    fun deviceSettings(action: String, value: Int, text: String): String = guarded {
        val ctx = context()
        val key = action.trim().lowercase(Locale.US)
        when (key) {
            "status", "get" -> {
                val brightness = Settings.System.getInt(
                    ctx.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    -1,
                )
                val mode = Settings.System.getInt(
                    ctx.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    -1,
                )
                val rotation = Settings.System.getInt(
                    ctx.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    1,
                )
                val timeout = Settings.System.getInt(
                    ctx.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    0,
                )
                val audio = ctx.getSystemService(AudioManager::class.java)
                val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                "Brightness ${brightness}/255 (${if (mode == 1) "auto" else "manual"}). " +
                    "Volume $volume/$max. Rotation ${if (rotation == 1) "auto" else "locked"}. " +
                    "Screen timeout ${timeout / 1000}s. DND filter ${nm.currentInterruptionFilter}. " +
                    "Torch ${if (torchOn) "on" else "off"}. Write settings: " +
                    "${Settings.System.canWrite(ctx)}."
            }
            "brightness" -> {
                if (!Settings.System.canWrite(ctx)) {
                    return@guarded "Write Settings permission is not granted. Open Access and grant it."
                }
                val level = (value.coerceIn(0, 100) * 255 / 100)
                Settings.System.putInt(
                    ctx.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                "Brightness set to ${value.coerceIn(0, 100)}%."
            }
            "rotation" -> {
                if (!Settings.System.canWrite(ctx)) {
                    return@guarded "Write Settings permission is not granted. Open Access and grant it."
                }
                val wanted = text.lowercase(Locale.US)
                when {
                    wanted.startsWith("auto") -> {
                        Settings.System.putInt(
                            ctx.contentResolver,
                            Settings.System.ACCELEROMETER_ROTATION,
                            1,
                        )
                        "Rotation set to automatic."
                    }
                    else -> {
                        Settings.System.putInt(
                            ctx.contentResolver,
                            Settings.System.ACCELEROMETER_ROTATION,
                            0,
                        )
                        val rotation = when {
                            wanted.startsWith("land") || wanted == "1" -> 1
                            wanted.startsWith("port") || wanted == "0" -> 0
                            else -> return@guarded "Use rotation auto, portrait or landscape."
                        }
                        Settings.System.putInt(
                            ctx.contentResolver,
                            "user_rotation",
                            rotation,
                        )
                        "Rotation locked to ${if (rotation == 1) "landscape" else "portrait"}."
                    }
                }
            }
            "timeout" -> {
                if (!Settings.System.canWrite(ctx)) {
                    return@guarded "Write Settings permission is not granted. Open Access and grant it."
                }
                val seconds = value.coerceIn(15, 3600)
                Settings.System.putInt(
                    ctx.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    seconds * 1000,
                )
                "Screen timeout set to $seconds seconds."
            }
            "volume" -> {
                val audio = ctx.getSystemService(AudioManager::class.java)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val level = (value.coerceIn(0, 100) * max / 100)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
                "Media volume set to ${value.coerceIn(0, 100)}%."
            }
            "flashlight", "torch" -> {
                val camera = ctx.getSystemService(CameraManager::class.java)
                val id = camera.cameraIdList.firstOrNull()
                    ?: return@guarded "No camera torch is available."
                val on = when {
                    text.contains("off", true) -> false
                    text.contains("on", true) -> true
                    value != 0 -> !torchOn
                    else -> !torchOn
                }
                camera.setTorchMode(id, on)
                torchOn = on
                "Torch ${if (on) "on" else "off"}."
            }
            "dnd" -> {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    val filter = when {
                        text.contains("all", true) || text.contains("off", true) ->
                            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                        text.contains("alarm", true) ->
                            android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
                        text.contains("none", true) ->
                            android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                        else -> android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    }
                    nm.setInterruptionFilter(filter)
                    "Do Not Disturb filter set."
                } else {
                    openSettings(ctx, Settings.ACTION_ZEN_MODE_ACCESS_SETTINGS)
                    "Grant DND access in the screen that opened."
                }
            }
            "wifi" -> openPanel(ctx, Settings.Panel.ACTION_WIFI)
            "internet" -> openPanel(ctx, Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            "bluetooth" -> openPanel(ctx, Settings.Panel.ACTION_BLUETOOTH)
            "volume_panel" -> openPanel(ctx, Settings.Panel.ACTION_VOLUME)
            "airplane" -> openSettings(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "location" -> openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "display" -> openSettings(ctx, Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> openSettings(ctx, Settings.ACTION_SOUND_SETTINGS)
            "battery" -> openSettings(ctx, Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "storage" -> openSettings(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "date" -> openSettings(ctx, Settings.ACTION_DATE_SETTINGS)
            "language" -> openSettings(ctx, Settings.ACTION_LOCALE_SETTINGS)
            "developer" -> openSettings(ctx, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            else -> "Unknown settings action: $action"
        }
    }

    @JvmStatic
    fun camera(action: String, path: String, cameraId: String, duration: Int): String = guarded {
        val ctx = context()
        return when (action.trim().lowercase(Locale.US)) {
            "list" -> {
                val manager = ctx.getSystemService(CameraManager::class.java)
                manager.cameraIdList.joinToString("\n") { id ->
                    val c = manager.getCameraCharacteristics(id)
                    val facing = c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    val facingName = if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                        "front"
                    } else {
                        "back"
                    }
                    "$id - $facingName"
                }.ifBlank { "No cameras reported." }
            }
            "photo", "take_photo", "capture" -> {
                val file = captureFile(ctx, path, "IMG", "jpg")
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(intent)
                "Camera opened. Photo will be saved to ${file.absolutePath}."
            }
            "video", "record" -> {
                val file = captureFile(ctx, path, "VID", "mp4")
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (duration > 0) intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, duration)
                ctx.startActivity(intent)
                "Camera opened. Video will be saved to ${file.absolutePath}."
            }
            "torch" -> deviceSettings("flashlight", if (path.contains("off", true)) 0 else 1, path)
            else -> "Unknown camera action: $action"
        }
    }

    @JvmStatic
    fun share(text: String, path: String, packageName: String): String = guarded {
        val ctx = context()
        val intent = if (path.isNotBlank()) {
            val file = resolve(path)
            if (!file.isFile) return@guarded "File not found: ${file.absolutePath}"
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            Intent(Intent.ACTION_SEND).apply {
                type = ctx.contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName.isNotBlank()) {
            intent.setPackage(packageName)
            ctx.startActivity(intent)
        } else {
            ctx.startActivity(Intent.createChooser(intent, "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        "Share sheet opened."
    }

    @JvmStatic
    fun fileAppend(path: String, content: String): String = guarded {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        file.appendText(content)
        "Appended ${content.length} characters to ${file.absolutePath}."
    }

    @JvmStatic
    fun fileReplace(path: String, find: String, replace: String, all: Boolean): String = guarded {
        if (find.isEmpty()) return@guarded "Nothing to replace."
        val file = resolve(path)
        if (!file.isFile) return@guarded "File not found: ${file.absolutePath}"
        val text = file.readText()
        val count = if (all) {
            text.windowed(find.length, 1, true).count { it == find }
        } else {
            if (text.contains(find)) 1 else 0
        }
        val updated = if (all) text.replace(find, replace) else text.replaceFirst(find, replace)
        file.writeText(updated)
        "Replaced $count occurrence${if (count == 1) "" else "s"} in ${file.absolutePath}."
    }

    @JvmStatic
    fun fileSearch(directory: String, query: String): String = guarded {
        val root = resolve(directory)
        if (!root.isDirectory) return@guarded "Not a directory: ${root.absolutePath}"
        root.walkTopDown()
            .maxDepth(7)
            .filter { it.name.contains(query, true) }
            .take(200)
            .joinToString("\n") { it.absolutePath }
            .ifBlank { "No files matched $query." }
    }

    @JvmStatic
    fun storageLocations(): String = guarded {
        val ctx = context()
        listOf(
            "internal files" to ctx.filesDir,
            "internal cache" to ctx.cacheDir,
            "external files" to (ctx.getExternalFilesDir(null) ?: File("/")),
            "external cache" to (ctx.externalCacheDir ?: File("/")),
            "shared storage" to Environment.getExternalStorageDirectory(),
            "downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        ).joinToString("\n") { (label, file) -> "$label: ${file.absolutePath}" }
    }

    @JvmStatic
    fun downloadUrl(url: String, path: String): String = guarded {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return@guarded "Only http and https URLs are supported."
        }
        val target = resolve(path)
        target.parentFile?.mkdirs()
        val connection = (URL(clean).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Jarvis-Android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                return@guarded "Download failed with HTTP $code."
            }
            val total = connection.contentLengthLong
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    while (written < 250L * 1024 * 1024) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                    }
                }
            }
            "Downloaded ${written / 1024} KB to ${target.absolutePath}" +
                if (total > 0) " (server size ${total / 1024} KB)." else "."
        } finally {
            connection.disconnect()
        }
    }

    @JvmStatic
    fun appDetails(packageName: String): String = guarded {
        val ctx = context()
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(
            packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA,
        )
        val app = info.applicationInfo
        val permissions = info.requestedPermissions?.joinToString(", ") ?: ""
        "Package: $packageName\n" +
            "Label: ${pm.getApplicationLabel(app)}\n" +
            "Version: ${info.versionName ?: "?"} (${info.longVersionCode})\n" +
            "Installed: ${Date(info.firstInstallTime)}\n" +
            "Updated: ${Date(info.lastUpdateTime)}\n" +
            "Enabled: ${app.enabled}\n" +
            "Permissions: ${permissions.ifBlank { "none" }}"
    }

    @JvmStatic
    fun isRooted(): String = rootStatus()

    @JvmStatic
    fun checkForUpdates(): String = guarded {
        CodeUpdater.checkForCode(context(), com.jarvis.assistant.SettingsStore.load(context()).repo)
    }

    @JvmStatic
    fun applyCodeUpdate(): String = guarded {
        val message = CodeUpdater.checkForCode(
            context(),
            com.jarvis.assistant.SettingsStore.load(context()).repo,
        )
        if (message.contains("Downloaded")) {
            com.jarvis.assistant.engine.JarvisEngine.reload()
        }
        message
    }

    @JvmStatic
    fun installApkUpdate(): String = guarded {
        CodeUpdater.installApkUpdate(context(), com.jarvis.assistant.SettingsStore.load(context()).repo)
    }

    private fun mediaKey(code: Int): String {
        val audio = context().getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Media key sent."
    }

    private fun openSettings(ctx: Context, action: String): String {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened settings."
    }

    private fun openPanel(ctx: Context, action: String): String {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened panel."
    }

    private fun captureFile(ctx: Context, path: String, prefix: String, extension: String): File {
        if (path.isNotBlank()) {
            val requested = resolve(path)
            requested.parentFile?.mkdirs()
            return requested
        }
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(ctx.filesDir, "captures")
        val directory = if (extension.equals("mp4", true)) {
            ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: base
        } else {
            base
        }
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(directory, "${prefix}_$stamp.$extension")
    }

    private fun resolve(path: String): File {
        val clean = path.trim()
        if (clean.startsWith("~")) {
            val base = context().getExternalFilesDir(null) ?: context().filesDir
            return File(base, clean.removePrefix("~").removePrefix("/"))
        }
        return File(clean)
    }

    private fun rootStatus(): String {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
        )
        return if (candidates.any { File(it).exists() }) "su present" else "no su"
    }

    private fun hasRoot(): Boolean = rootStatus() == "su present"

    private fun ipAddress(): String {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress ?: "unknown"
        } catch (e: Throwable) {
            "unknown"
        }
    }

    private inline fun guarded(block: () -> String): String {
        return try {
            block()
        } catch (e: Throwable) {
            "ERROR: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
