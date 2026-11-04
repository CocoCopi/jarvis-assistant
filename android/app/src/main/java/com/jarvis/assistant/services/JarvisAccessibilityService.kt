package com.jarvis.assistant.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun dumpUi(): String {
        val root = rootInActiveWindow ?: return "No active window."
        val builder = StringBuilder()
        val counter = intArrayOf(0)
        walk(root, 0, builder, counter)
        if (builder.isEmpty()) return "The screen has no readable labels."
        return builder.toString().take(30_000)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        builder: StringBuilder,
        counter: IntArray,
    ) {
        if (counter[0] >= 250) return
        counter[0] += 1
        val label = listOfNotNull(
            node.text?.toString()?.trim(),
            node.contentDescription?.toString()?.trim(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim()
            } else {
                null
            },
        ).firstOrNull { it.isNotEmpty() }
        if (!label.isNullOrEmpty()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val cx = (bounds.left + bounds.right) / 2
            val cy = (bounds.top + bounds.bottom) / 2
            val flags = buildString {
                if (node.isClickable) append(" [click]")
                if (node.isEditable) append(" [edit]")
                if (node.isFocused) append(" [focus]")
            }
            val id = node.viewIdResourceName?.substringAfterLast('/')?.let { " #$it" } ?: ""
            builder.append("  ".repeat(depth.coerceAtMost(12)))
                .append(label)
                .append(" @ (").append(cx).append(", ").append(cy).append(")")
                .append(flags)
                .append(id)
                .append(" [").append(node.className?.toString()?.substringAfterLast('.') ?: "?").append("]")
                .append('\n')
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, depth + 1, builder, counter)
            if (counter[0] >= 250) break
        }
    }

    fun clickText(text: String, exact: Boolean): String {
        val root = rootInActiveWindow ?: return "No active window."
        val target = findNode(root, text, exact) ?: return "No visible element matching '$text'."
        var clickable: AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        return if (ok) "Clicked '$text'." else "Found '$text' but could not click it."
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            visited += 1
            val node = queue.removeFirst()
            val label = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            val matches = if (exact) label.equals(text, ignoreCase = true)
            else label.contains(text, ignoreCase = true)
            if (matches && label.isNotBlank()) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun tap(x: Int, y: Int): String {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(path, 0, 80, "Tap $x, $y")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): String {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(path, 0, duration.coerceIn(50, 5000), "Swipe $x1,$y1 to $x2,$y2")
    }

    private fun gesture(path: Path, start: Long, duration: Int, label: String): String {
        val stroke = GestureDescription.StrokeDescription(path, start, duration.toLong())
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var completed = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        val dispatched = dispatchGesture(description, callback, null)
        if (!dispatched) return "$label failed: gesture not dispatched."
        latch.await(10, TimeUnit.SECONDS)
        return if (completed) "$label done." else "$label cancelled."
    }

    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "No active window."
        val node = findFocusedEditable(root) ?: findFirstEditable(root)
            ?: return "No editable text field is visible."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (ok) "Typed ${text.length} characters." else "Could not set text."
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            visited += 1
            val node = queue.removeFirst()
            if (node.isEditable && node.isFocused) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            visited += 1
            val node = queue.removeFirst()
            if (node.isEditable) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun key(name: String): String {
        val key = name.lowercase(Locale.US)
        val global = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent" -> GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings", "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock", "screen_off" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                GLOBAL_ACTION_LOCK_SCREEN
            } else {
                -1
            }
            else -> -1
        }
        if (global != -1) {
            val ok = performGlobalAction(global)
            return if (ok) "Pressed $key." else "Could not press $key."
        }
        val root = rootInActiveWindow ?: return "No active window for key $key."
        if (key == "delete" || key == "backspace") {
            val node = findFocusedEditable(root) ?: return "No focused text field."
            val current = node.text?.toString().orEmpty().dropLast(1)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            return "Deleted one character."
        }
        if (key == "enter" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val node = findFocusedEditable(root) ?: return "No focused text field."
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
            return if (ok) "Pressed enter." else "Could not press enter."
        }
        return "Unsupported key: $name"
    }

    fun screenshot(path: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Screenshots require Android 11 or later."
        }
        val latch = CountDownLatch(1)
        var message = "Screenshot failed."
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )?.copy(Bitmap.Config.ARGB_8888, true)
                        screenshot.hardwareBuffer.close()
                        if (bitmap == null) {
                            message = "Could not decode screenshot."
                        } else {
                            val target = if (path.isBlank()) defaultScreenshotPath() else File(path)
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            }
                            bitmap.recycle()
                            message = "Screenshot saved to ${target.absolutePath}."
                        }
                    } catch (e: Throwable) {
                        message = "Screenshot failed: ${e.message}"
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    message = "Screenshot failed with code $errorCode."
                    latch.countDown()
                }
            },
        )
        latch.await(20, TimeUnit.SECONDS)
        return message
    }

    private fun defaultScreenshotPath(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        val directory = File(base, "screenshots").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(directory, "jarvis-$stamp.png")
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }
}
