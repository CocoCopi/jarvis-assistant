package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class JarvisBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 92, 219, 255)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(16, 92, 219, 255)
        strokeWidth = dp(0.7f)
    }
    private var running = false

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

        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.rgb(4, 7, 12),
                Color.rgb(7, 15, 26),
                Color.rgb(3, 8, 14),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        val breathe = 0.82f + 0.18f * sin((now / 2400.0)).toFloat()
        paint.shader = RadialGradient(
            w * 0.78f, h * 0.12f,
            w * 0.72f,
            intArrayOf(
                Color.argb((46 * breathe).toInt(), 31, 182, 224),
                Color.argb(0, 31, 182, 224),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.78f, h * 0.12f, w * 0.72f, paint)

        paint.shader = RadialGradient(
            w * 0.05f, h * 0.74f,
            w * 0.64f,
            intArrayOf(
                Color.argb(32, 255, 190, 90),
                Color.argb(0, 255, 190, 90),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.05f, h * 0.74f, w * 0.64f, paint)
        paint.shader = null

        val step = dp(42f)
        var y = step
        while (y < h) {
            var x = step
            while (x < w) {
                canvas.drawCircle(x, y, dp(0.85f), dotPaint)
                x += step
            }
            y += step
        }

        val drift = sin((now / 4800.0)).toFloat() * dp(22f)
        canvas.drawLine(-dp(20f), h * 0.33f + drift, w * 0.62f, -dp(10f) + drift, linePaint)
        canvas.drawLine(w, h * 0.62f + drift, w * 0.35f, h + dp(30f) + drift, linePaint)

        if (running) postInvalidateOnAnimation()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
