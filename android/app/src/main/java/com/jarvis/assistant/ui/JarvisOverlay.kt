package com.jarvis.assistant.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.services.JarvisAccessibilityService
import kotlin.math.sin

object JarvisOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: IslandView? = null
    private var manager: WindowManager? = null
    private var host: Context? = null
    private var lastState = "idle"
    private var lastDetail = "At your service, sir."

    fun attach(context: Context) {
        main.post { attachNow(context.applicationContext) }
    }

    fun update(state: String, detail: String) {
        lastState = state
        lastDetail = detail.ifBlank { "At your service, sir." }
        main.post {
            val current = view
            if (current == null) {
                attachNow(com.jarvis.assistant.JarvisApp.instance.applicationContext)
            }
            (view ?: current)?.render(lastState, lastDetail)
        }
    }

    fun ensure(context: Context) {
        main.post { attachNow(context.applicationContext) }
    }

    fun detach() {
        main.post { detachNow() }
    }

    private fun attachNow(appContext: Context) {
        if (view != null) {
            view?.render(lastState, lastDetail)
            return
        }
        val access = JarvisAccessibilityService.instance
        val hostContext: Context
        val type: Int
        if (access != null) {
            hostContext = access
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(appContext)) {
            hostContext = appContext
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            return
        }
        val wm = hostContext.getSystemService(WindowManager::class.java) ?: return
        val island = IslandView(hostContext).apply {
            render(lastState, lastDetail)
            setOnClickListener {
                hostContext.startActivity(
                    Intent(hostContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(hostContext, 7)
            setTitle("Jarvis island")
        }
        try {
            wm.addView(island, params)
            view = island
            manager = wm
            host = hostContext
            island.alpha = 0f
            island.scaleX = 0.86f
            island.scaleY = 0.86f
            island.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .start()
        } catch (_: Throwable) {
            view = null
            manager = null
            host = null
        }
    }

    private fun detachNow() {
        val island = view ?: return
        val wm = manager
        view = null
        manager = null
        host = null
        try {
            wm?.removeView(island)
        } catch (_: Throwable) {
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class IslandView(context: Context) : View(context) {
        private val paper = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = sp(7.5f)
        }
        private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = sp(12f)
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var label = "J A R V I S"
        private var detail = "At your service, sir."
        private var mode = "idle"
        private var accent = Color.rgb(23, 107, 135)
        private var active = false

        init {
            isClickable = true
            isFocusable = false
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun render(state: String, text: String) {
            mode = state.lowercase()
            detail = text.replace('\n', ' ').take(70).ifBlank { "At your service, sir." }
            accent = when {
                mode.contains("think") || mode.contains("process") -> Color.rgb(169, 113, 66)
                mode.contains("speak") -> Color.rgb(46, 107, 85)
                mode.contains("wait") || mode.contains("stand") -> Color.rgb(112, 118, 122)
                mode.contains("error") || mode.contains("unavailable") -> Color.rgb(163, 58, 49)
                else -> Color.rgb(23, 107, 135)
            }
            active = mode.contains("listen") || mode.contains("hear") ||
                mode.contains("think") || mode.contains("process") || mode.contains("speak") ||
                mode.contains("arm")
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val detailWidth = detailPaint.measureText(detail).toInt()
            val labelWidth = labelPaint.measureText(label).toInt()
            val desired = maxOf(dp(196f).toInt(), maxOf(detailWidth, labelWidth) + dp(82f).toInt())
            val max = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            setMeasuredDimension(minOf(desired, max), dp(48f).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f
            rect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))

            paper.color = Color.rgb(251, 250, 246)
            paper.setShadowLayer(dp(12f), 0f, dp(3f), Color.argb(34, 0, 0, 0))
            canvas.drawRoundRect(rect, radius, radius, paper)
            paper.clearShadowLayer()
            paper.color = Color.rgb(251, 250, 246)
            canvas.drawRoundRect(rect, radius, radius, paper)

            border.color = if (active) withAlpha(accent, 120) else Color.argb(52, 24, 27, 29)
            canvas.drawRoundRect(rect, radius, radius, border)

            val pulse = if (active) 0.5f + 0.5f * sin((now / 300.0)).toFloat() else 0.2f
            dotPaint.color = withAlpha(accent, if (active) (170 + 70 * pulse).toInt() else 160)
            canvas.drawCircle(dp(19f), h / 2f, dp(2.6f + 0.5f * pulse), dotPaint)

            labelPaint.color = withAlpha(accent, 190)
            detailPaint.color = Color.rgb(24, 27, 29)
            val textLeft = dp(33f)
            canvas.drawText(label, textLeft, h / 2f - dp(3.5f), labelPaint)

            val available = w - textLeft - dp(46f)
            val shown = if (detailPaint.measureText(detail) > available) {
                ellipsize(detail, available)
            } else {
                detail
            }
            canvas.drawText(shown, textLeft, h / 2f + dp(11.5f), detailPaint)

            if (active) {
                var x = w - dp(28f)
                for (i in 0 until 4) {
                    val level = 0.28f + 0.72f * kotlin.math.abs(
                        sin((now / (190f + i * 47f) + i * 0.8f).toDouble()).toFloat(),
                    )
                    val barHeight = dp(13f) * level
                    barPaint.color = withAlpha(accent, 190)
                    val left = x + i * dp(5f)
                    canvas.drawRoundRect(
                        left,
                        h / 2f - barHeight / 2f,
                        left + dp(2.2f),
                        h / 2f + barHeight / 2f,
                        dp(2f),
                        dp(2f),
                        barPaint,
                    )
                }
                postInvalidateOnAnimation()
            }
        }

        private fun ellipsize(text: String, width: Float): String {
            if (detailPaint.measureText(text) <= width) return text
            var end = text.length
            while (end > 1 && detailPaint.measureText(text.substring(0, end) + "…") > width) {
                end -= 1
            }
            return text.substring(0, end) + "…"
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(
                alpha.coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )

        private fun dp(value: Float): Float = value * resources.displayMetrics.density

        private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    }
}
