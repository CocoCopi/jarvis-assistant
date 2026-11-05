package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class JarvisBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(14, 23, 107, 135)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        color = Color.argb(16, 23, 107, 135)
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(0.7f)
        color = Color.argb(11, 24, 27, 29)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.rgb(248, 245, 239),
                Color.rgb(244, 241, 235),
                Color.rgb(241, 237, 229),
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)

        glow.shader = RadialGradient(
            w * 0.86f, h * 0.02f, w * 0.78f,
            intArrayOf(Color.argb(34, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.86f, h * 0.02f, w * 0.78f, glow)

        glow.shader = RadialGradient(
            w * 0.08f, h * 0.9f, w * 0.5f,
            intArrayOf(Color.argb(16, 23, 107, 135), Color.argb(0, 23, 107, 135)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.08f, h * 0.9f, w * 0.5f, glow)
        glow.shader = null

        val step = dp(44f)
        var y = step
        while (y < h) {
            var x = step
            while (x < w) {
                canvas.drawCircle(x, y, dp(0.75f), dot)
                x += step
            }
            y += step
        }

        canvas.drawCircle(w * 1.02f, -h * 0.02f, w * 0.62f, ring)
        canvas.drawCircle(w * 1.02f, -h * 0.02f, w * 0.78f, ring)
        canvas.drawCircle(w * 1.02f, -h * 0.02f, w * 0.94f, ring)
        canvas.drawCircle(-w * 0.16f, h * 1.02f, w * 0.42f, ring)
        canvas.drawCircle(-w * 0.16f, h * 1.02f, w * 0.56f, ring)

        canvas.drawLine(w * 0.58f, 0f, w, h * 0.18f, hairline)
        canvas.drawLine(0f, h * 0.78f, w * 0.34f, h, hairline)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
