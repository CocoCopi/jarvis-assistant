package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class JarvisOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(1.8f)
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var mode = "idle"
    private var accent = Color.rgb(92, 219, 255)
    private var running = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setMode(state: String) {
        val normalized = state.lowercase()
        mode = normalized
        accent = when {
            normalized.contains("think") || normalized.contains("process") -> Color.rgb(255, 209, 102)
            normalized.contains("speak") -> Color.rgb(92, 255, 192)
            normalized.contains("wait") || normalized.contains("stand") || normalized == "stopped" -> Color.rgb(112, 151, 175)
            normalized.contains("error") || normalized.contains("unavailable") -> Color.rgb(255, 107, 129)
            else -> Color.rgb(92, 219, 255)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val size = min(w, h)
        val radius = size * 0.31f
        val active = mode.contains("listen") || mode.contains("hear") ||
            mode.contains("think") || mode.contains("process") ||
            mode.contains("speak") || mode.contains("arm")
        val pulse = if (active) {
            0.5f + 0.5f * sin((now / 420.0)).toFloat()
        } else {
            0.25f
        }

        glow.shader = RadialGradient(
            cx, cy, radius * 1.7f,
            intArrayOf(
                withAlpha(accent, (80 + 60 * pulse).toInt()),
                withAlpha(accent, (26 + 20 * pulse).toInt()),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius * 1.7f, glow)
        glow.shader = null

        for (i in 0 until 3) {
            val ringRadius = radius * (1.05f + i * 0.23f)
            ring.color = withAlpha(accent, (70 - i * 17).coerceAtLeast(16))
            ring.strokeWidth = dp(if (i == 0) 1.4f else 0.8f)
            arc.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            val rotation = now / (if (i == 1) 5200f else 7200f) * 360f
            canvas.save()
            canvas.rotate(rotation + i * 43f, cx, cy)
            canvas.drawArc(
                arc,
                -18f,
                76f + i * 11f,
                false,
                ring,
            )
            canvas.restore()
        }

        canvas.save()
        canvas.rotate(-now / 38f, cx, cy)
        for (i in 0 until 36) {
            val angle = Math.toRadians((i * 10).toDouble())
            val inner = radius * 1.29f
            val outer = inner + if (i % 3 == 0) dp(9f) else dp(4f)
            tick.color = withAlpha(accent, if (i % 3 == 0) 150 else 52)
            canvas.drawLine(
                cx + cos(angle).toFloat() * inner,
                cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * outer,
                cy + sin(angle).toFloat() * outer,
                tick,
            )
        }
        canvas.restore()

        core.shader = RadialGradient(
            cx - radius * 0.25f,
            cy - radius * 0.3f,
            radius * 1.35f,
            intArrayOf(
                Color.rgb(238, 253, 255),
                withAlpha(accent, 235),
                withAlpha(accent, 70),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.28f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, core)
        core.shader = null

        dot.color = withAlpha(accent, (120 + 80 * pulse).toInt())
        val orbit = radius * 1.58f
        val orbitAngle = Math.toRadians((now / 12.0) % 360.0)
        canvas.drawCircle(
            cx + cos(orbitAngle).toFloat() * orbit,
            cy + sin(orbitAngle).toFloat() * orbit,
            dp(2.4f),
            dot,
        )

        if (running) postInvalidateOnAnimation()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
