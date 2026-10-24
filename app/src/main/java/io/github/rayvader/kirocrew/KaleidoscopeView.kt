package io.github.rayvader.kirocrew

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Rainbow star rings on black that keep growing out of the middle, for the
 * Credits screen. The rings turn slowly, each a little ahead of the next. With
 * animations off on the phone (Settings > Accessibility > Remove animations)
 * it stays still.
 */
class KaleidoscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val hsv = floatArrayOf(0f, 1f, 1f)
    private val spacing = 14f * resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val moving = ValueAnimator.areAnimatorsEnabled()
        val t = if (moving) (SystemClock.uptimeMillis() - start) / 1000f else STILL_TIME
        val cx = width / 2f
        val cy = height / 2f
        val maxR = hypot(cx, cy) / INNER + spacing

        // Rings move out one spacing every RING_SECONDS; `id` follows a ring
        // as it moves, so its colour and twist stay with it.
        val s = t / RING_SECONDS
        val base = floor(s).toInt()
        val phase = s - base
        val count = (maxR / spacing).toInt() + 2
        for (i in count downTo 0) {
            val r = (i + phase) * spacing
            if (r <= 0f) continue
            val id = i - base
            paint.color = if ((id and 1) == 0) {
                hsv[0] = (((id * 17 + t * 40) % 360f) + 360f) % 360f
                Color.HSVToColor(hsv)
            } else {
                Color.BLACK
            }
            val spin = t * SPIN + id * TWIST
            star(cx, cy, r, spin)
            canvas.drawPath(path, paint)
        }
        if (moving && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    private fun star(cx: Float, cy: Float, r: Float, spin: Float) {
        path.rewind()
        for (j in 0 until POINTS * 2) {
            val a = spin + j * PI.toFloat() / POINTS
            val rr = if (j % 2 == 0) r else r * INNER
            val x = cx + rr * cos(a)
            val y = cy + rr * sin(a)
            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private companion object {
        const val POINTS = 12
        const val INNER = 0.8f
        const val RING_SECONDS = 0.9f
        const val SPIN = 0.3f
        const val TWIST = 0.045f
        const val STILL_TIME = 7.3f
    }
}
