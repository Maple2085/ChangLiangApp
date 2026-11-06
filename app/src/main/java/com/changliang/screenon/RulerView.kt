package com.changliang.screenon

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 调表板的刻度尺：左右拖动，松手吸附到最近一格，可甩动。
 * 步长随数值变：≤30 分钟每 1 分钟，30–120 每 5 分钟，120–720 每 10 分钟。
 */
class RulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MIN = 1
        const val MAX = 720
        val VALUES: IntArray = buildList {
            for (m in 1..30) add(m)
            for (m in 35..120 step 5) add(m)
            for (m in 130..720 step 10) add(m)
        }.toIntArray()

        fun indexOf(minutes: Int): Int {
            var best = 0
            for (i in VALUES.indices) if (abs(VALUES[i] - minutes) < abs(VALUES[best] - minutes)) best = i
            return best
        }

        fun label(m: Int): String? = when {
            m <= 30 -> if (m % 5 == 0 || m == 1) "$m" else null
            m <= 120 -> when (m) { 60 -> "1H"; 90 -> "90"; 120 -> "2H"; 45 -> "45"; else -> null }
            else -> if (m % 60 == 0) "${m / 60}H" else null
        }
    }

    var onChange: ((Int) -> Unit)? = null

    private val spacing = dp(11f)
    /** 当前位置，单位：格（可以是小数，拖动中） */
    private var pos = indexOf(30).toFloat()
    private var lastIndex = pos.toInt()
    private var anim: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var lastX = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()

    val value get() = VALUES[pos.roundToInt().coerceIn(0, VALUES.lastIndex)]

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), dp(92f).toInt())

    fun setValue(minutes: Int, animate: Boolean) {
        val target = indexOf(minutes.coerceIn(MIN, MAX)).toFloat()
        anim?.cancel()
        if (!animate) { pos = target; settle(false); invalidate(); return }
        runTo(target, 260L)
    }

    private fun runTo(target: Float, dur: Long) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = dur
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { pos = it.animatedValue as Float; settle(true); invalidate() }
            start()
        }
    }

    /** 跨过一格就震一下并回调 */
    private fun settle(haptic: Boolean) {
        val i = pos.roundToInt().coerceIn(0, VALUES.lastIndex)
        if (i != lastIndex) {
            lastIndex = i
            if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            contentDescription = "自定义时长 ${VALUES[i]} 分钟"
            onChange?.invoke(VALUES[i])
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                anim?.cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                lastX = e.x
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.x - lastX
                lastX = e.x
                pos = (pos - dx / spacing).coerceIn(-0.4f, VALUES.lastIndex + 0.4f)
                settle(true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                tracker?.recycle(); tracker = null
                // 甩动：按速度多滑一段，再吸附
                val extra = if (abs(vx) > dp(300f)) -vx / spacing * 0.28f else 0f
                val target = (pos + extra).roundToInt().coerceIn(0, VALUES.lastIndex).toFloat()
                val dur = (180L + abs(target - pos) * 9f).toLong().coerceAtMost(900L)
                runTo(target, dur)
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f

        rect.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Pal.PANEL
        c.drawRoundRect(rect, dp(14f), dp(14f), p)

        val top = dp(14f)
        val first = (pos - cx / spacing - 2).toInt().coerceAtLeast(0)
        val last = (pos + cx / spacing + 2).toInt().coerceAtMost(VALUES.lastIndex)
        p.strokeCap = Paint.Cap.ROUND
        t.typeface = Pal.COND_BOLD
        t.textSize = dp(12f)
        for (i in first..last) {
            val x = cx + (i - pos) * spacing
            val m = VALUES[i]
            val lab = label(m)
            val edge = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)
            val a = edge * edge
            val red = m > 120
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (lab != null) dp(2f) else dp(1.2f)
            p.color = Pal.alpha(if (red) Pal.RED else if (lab != null) Pal.WHITE else Pal.STEEL, a)
            val len = if (lab != null) dp(26f) else dp(14f)
            c.drawLine(x, top, x, top + len, p)
            if (lab != null) {
                t.color = Pal.alpha(if (red) Pal.RED_HOT else Pal.STEEL, a)
                c.drawText(lab, x, top + dp(46f), t)
            }
        }

        // 两侧渐隐
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, dp(36f), 0f, Pal.PANEL, 0x000D0F13, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, dp(36f), h, p)
        p.shader = LinearGradient(w - dp(36f), 0f, w, 0f, 0x000D0F13, Pal.PANEL, Shader.TileMode.CLAMP)
        c.drawRect(w - dp(36f), 0f, w, h, p)
        p.shader = null

        // 中央指示
        p.color = Pal.alpha(Pal.RED, 0.25f)
        c.drawRect(cx - dp(5f), dp(6f), cx + dp(5f), top + dp(32f), p)
        p.color = Pal.RED_HOT
        c.drawRect(cx - dp(1.5f), dp(6f), cx + dp(1.5f), top + dp(32f), p)
        path.reset()
        path.moveTo(cx - dp(7f), dp(2f)); path.lineTo(cx + dp(7f), dp(2f)); path.lineTo(cx, dp(10f)); path.close()
        c.drawPath(path, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1f)
        p.color = Pal.LINE
        c.drawRoundRect(rect, dp(14f), dp(14f), p)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
