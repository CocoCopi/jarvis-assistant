package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var mode = "idle"
    private var accent = Color.rgb(23, 107, 135)
    private var running = false

    fun setMode(state: String) {
        val normalized = state.lowercase()
        mode = normalized
        accent = when {
            normalized.contains("think") || normalized.contains("process") -> Color.rgb(169, 113, 66)
            normalized.contains("speak") -> Color.rgb(46, 107, 85)
            normalized.contains("wait") || normalized.contains("stand") || normalized == "stopped" ->
                Color.rgb(112, 118, 122)
            normalized.contains("error") || normalized.contains("unavailable") ->
                Color.rgb(163, 58, 49)
            else -> Color.rgb(23, 107, 135)
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
        val radius = size * 0.30f
        val active = mode.contains("listen") || mode.contains("hear") ||
            mode.contains("think") || mode.contains("process") ||
            mode.contains("speak") || mode.contains("arm")
        val pulse = 0.5f + 0.5f * sin((now / 320.0)).toFloat()

        line.color = withAlpha(accent, if (active) 58 else 34)
        line.strokeWidth = dp(0.9f)
        canvas.drawCircle(cx, cy, radius * 1.34f, line)

        for (i in 0 until 3) {
            val ringRadius = radius * (1.05f + i * 0.13f)
            line.color = withAlpha(accent, (if (active) 135 else 64) - i * 17)
            line.strokeWidth = dp(if (i == 0) 1.1f else 0.75f)
            arc.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            canvas.save()
            canvas.rotate(
                now / (if (i == 1) 6400f else 9400f) * 360f + i * 47f,
                cx,
                cy,
            )
            canvas.drawArc(arc, -24f, 62f + i * 14f, false, line)
            canvas.restore()
        }

        canvas.save()
        canvas.rotate(-now / 54f, cx, cy)
        val tickRadius = radius * 1.34f
        for (i in 0 until 72) {
            val angle = Math.toRadians((i * 5).toDouble())
            val inner = tickRadius + dp(3.5f)
            val outer = inner + if (i % 6 == 0) dp(6.5f) else dp(2.2f)
            tick.color = withAlpha(accent, if (i % 6 == 0) 92 else 26)
            tick.strokeWidth = if (i % 6 == 0) dp(1.1f) else dp(0.65f)
            canvas.drawLine(
                cx + cos(angle).toFloat() * inner,
                cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * outer,
                cy + sin(angle).toFloat() * outer,
                tick,
            )
        }
        canvas.restore()

        line.color = withAlpha(accent, if (active) 82 else 38)
        line.strokeWidth = dp(0.8f)
        canvas.drawCircle(cx, cy, radius * 0.74f, line)
        canvas.drawCircle(cx, cy, radius * 0.44f, line)

        core.color = withAlpha(accent, if (active) (120 + 70 * pulse).toInt() else 80)
        canvas.drawCircle(cx, cy, dp(if (active) 2.4f + pulse * 1.2f else 2.4f), core)

        line.color = withAlpha(accent, if (active) 70 else 30)
        line.strokeWidth = dp(0.7f)
        canvas.drawLine(cx - dp(11f), cy, cx - dp(5.5f), cy, line)
        canvas.drawLine(cx + dp(5.5f), cy, cx + dp(11f), cy, line)
        canvas.drawLine(cx, cy - dp(11f), cx, cy - dp(5.5f), line)
        canvas.drawLine(cx, cy + dp(5.5f), cx, cy + dp(11f), line)

        if (active) {
            val orbit = radius * 1.13f
            val angle = Math.toRadians((now / 16.0) % 360.0)
            core.color = withAlpha(accent, 190)
            canvas.drawCircle(
                cx + cos(angle).toFloat() * orbit,
                cy + sin(angle).toFloat() * orbit,
                dp(1.8f),
                core,
            )
        }

        if (running) postInvalidateDelayed(33L)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
