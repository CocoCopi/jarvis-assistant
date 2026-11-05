package com.jarvis.assistant.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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

object JarvisOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: IslandView? = null
    private var manager: WindowManager? = null
    private var host: Context? = null
    private var lastState = "idle"
    private var lastDetail = "Standing by"

    fun attach(context: Context) {
        main.post { attachNow(context.applicationContext) }
    }

    fun update(state: String, detail: String) {
        lastState = state
        lastDetail = detail.ifBlank { "Standing by" }
        main.post {
            val current = view
            if (current == null) {
                attachNow(com.jarvis.assistant.JarvisApp.instance.applicationContext)
            }
            current?.render(lastState, lastDetail)
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
            island.scaleX = 0.82f
            island.scaleY = 0.82f
            island.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260L)
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
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = sp(8.5f)
        }
        private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = sp(12.5f)
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var label = "J A R V I S"
        private var detail = "Standing by"
        private var mode = "idle"
        private var accent = Color.rgb(92, 219, 255)
        private var active = false
        private var pulseAt = SystemClock.uptimeMillis()

        init {
            isClickable = true
            isFocusable = false
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun render(state: String, text: String) {
            mode = state.lowercase()
            detail = text.replace('\n', ' ').take(70).ifBlank { "Standing by" }
            accent = when {
                mode.contains("think") || mode.contains("process") -> Color.rgb(255, 209, 102)
                mode.contains("speak") -> Color.rgb(92, 255, 192)
                mode.contains("wait") || mode.contains("stand") -> Color.rgb(138, 169, 188)
                mode.contains("error") || mode.contains("unavailable") -> Color.rgb(255, 107, 129)
                else -> Color.rgb(92, 219, 255)
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
            val desired = maxOf(dp(200f).toInt(), maxOf(detailWidth, labelWidth) + dp(86f).toInt())
            val max = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            setMeasuredDimension(minOf(desired, max), dp(52f).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f
            rect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))

            if (active) {
                val pulse = (0.45f + 0.35f * kotlin.math.sin((now / 360f).toDouble()).toFloat())
                glowPaint.color = withAlpha(accent, (90 * pulse).toInt())
                glowPaint.setShadowLayer(dp(18f), 0f, 0f, withAlpha(accent, 180))
                canvas.drawRoundRect(rect, radius, radius, glowPaint)
                glowPaint.clearShadowLayer()
            }

            backgroundPaint.shader = LinearGradient(
                0f,
                0f,
                w,
                h,
                Color.argb(250, 10, 15, 21),
                Color.argb(250, 4, 7, 11),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
            strokePaint.color = withAlpha(accent, 130)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            val dotPulse = if (active) {
                3.8f + 1.4f * kotlin.math.sin((now / 260f).toDouble()).toFloat()
            } else {
                3.6f
            }
            dotPaint.color = accent
            canvas.drawCircle(dp(19f), h / 2f, dp(dotPulse), dotPaint)

            labelPaint.color = withAlpha(accent, 185)
            detailPaint.color = Color.rgb(228, 247, 255)
            val textLeft = dp(34f)
            canvas.drawText(
                label,
                textLeft,
                h / 2f - dp(3.5f),
                labelPaint,
            )
            val available = w - textLeft - dp(48f)
            val shown = if (detailPaint.measureText(detail) > available) {
                ellipsize(detail, available)
            } else {
                detail
            }
            canvas.drawText(shown, textLeft, h / 2f + dp(12f), detailPaint)

            if (active) {
                var x = w - dp(30f)
                for (i in 0 until 4) {
                    val level = 0.28f + 0.72f * kotlin.math.abs(
                        kotlin.math.sin(
                            (now / (190f + i * 47f) + i * 0.8f).toDouble(),
                        ).toFloat(),
                    )
                    val barHeight = dp(14f) * level
                    barPaint.color = withAlpha(accent, 215)
                    val left = x + i * dp(5.2f)
                    canvas.drawRoundRect(
                        left,
                        h / 2f - barHeight / 2f,
                        left + dp(2.4f),
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
