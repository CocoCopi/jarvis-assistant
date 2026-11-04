package com.jarvis.assistant.bridge

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
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
import java.net.Inet4Address
import java.net.NetworkInterface
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
    fun uiDump(): String = guarded {
        service()?.dumpUi() ?: "Accessibility service is not enabled. Open Access and enable Jarvis."
    }

    @JvmStatic
    fun clickText(text: String, exact: Boolean): String = guarded {
        service()?.clickText(text, exact)
            ?: "Accessibility service is not enabled. Open Access and enable Jarvis."
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
            else -> service()?.key(key) ?: "Accessibility service is not enabled."
        }
    }

    @JvmStatic
    fun screenshot(path: String): String = guarded {
        service()?.screenshot(path)
            ?: "Accessibility service is not enabled. Open Access and enable Jarvis."
    }

    @JvmStatic
    fun notifications(): String = guarded {
        JarvisNotificationService.instance?.dump()
            ?: "Notification listener is not enabled. Open Access and enable Jarvis notifications."
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
