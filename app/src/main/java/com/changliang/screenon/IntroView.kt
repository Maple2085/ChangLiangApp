package com.changliang.screenon

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 入场动画：通电扫描线 → 五盏发车灯逐个点亮 → 熄灯起步 → 转速表扫到红线 → 回落到当前挡位
 * → 表盘和品牌字缩放飞入主界面里的真实位置。
 *
 * 表盘用的是 GaugeRenderer + 主界面 GaugeView 的同一份状态，品牌字用标题 TextView 的同一支画笔，
 * 所以最后一帧与真实控件逐像素重合，移除本 View 的那一帧看不出切换。
 */
@SuppressLint("ViewConstructor")
class IntroView(context: Context, private val host: Host) : View(context) {

    interface Host {
        val gauge: GaugeView
        val titleView: TextView
        val subtitleView: TextView
        fun onMorphStart()
        fun onIntroFinished()
    }

    companion object {
        private const val POWER_END = 420f
        private const val GANTRY_IN = 260f
        private const val LIGHT0 = 480f
        private const val LIGHT_STEP = 185f
        private const val LIGHTS_OUT = 1560f
        private const val REVEAL_START = 1600f
        private const val REVEAL_END = 2260f
        private const val FALL_END = 2720f
        private const val BRAND_IN = 2180f
        private const val MORPH_START = 2820f
        private const val TOTAL = 3440f
    }

    private val anim = ValueAnimator.ofFloat(0f, TOTAL)
    private var t = 0f
    private var lightsFired = 0
    private var outFired = false
    private var peakFired = false
    private var morphFired = false
    private var finished = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var titlePaint: TextPaint? = null
    private var subPaint: TextPaint? = null

    private val streaks = Array(26) { FloatArray(5) }

    init {
        val rnd = Random(7)
        for (s in streaks) {
            s[0] = rnd.nextFloat() * 2f - 1f        // 竖向偏移
            s[1] = 0.25f + rnd.nextFloat() * 0.6f   // 长度
            s[2] = 0.7f + rnd.nextFloat() * 0.8f    // 速度
            s[3] = if (rnd.nextBoolean()) 1f else -1f
            s[4] = 0.6f + rnd.nextFloat() * 1.6f    // 粗细
        }
        isClickable = true
        contentDescription = "入场动画，点按跳过"
        setBackgroundColor(0)
    }

    fun start() {
        anim.duration = TOTAL.toLong()
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { t = it.animatedValue as Float; onTick(); invalidate() }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) { finish() }
        })
        anim.start()
    }

    /** 点按或返回键：直接跳到变形阶段，不打断衔接 */
    fun skip() {
        if (finished || t >= MORPH_START) return
        lightsFired = 5; outFired = true; peakFired = true
        anim.currentPlayTime = (MORPH_START - 1f).toLong()
    }

    private fun onTick() {
        while (lightsFired < 5 && t >= LIGHT0 + LIGHT_STEP * lightsFired) {
            lightsFired++
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        if (!outFired && t >= LIGHTS_OUT) { outFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        if (!peakFired && t >= REVEAL_END) { peakFired = true; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        if (!morphFired && t >= MORPH_START) { morphFired = true; host.onMorphStart() }
    }

    private fun finish() {
        if (finished) return
        finished = true
        if (!morphFired) { morphFired = true; host.onMorphStart() }
        host.onIntroFinished()
    }

    override fun onDetachedFromWindow() {
        finished = true
        anim.removeAllListeners()
        anim.removeAllUpdateListeners()
        anim.cancel()
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) skip()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); skip(); return true }

    // ---------- 缓动 ----------
    private fun seg(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun easeOut(x: Float) = 1f - (1f - x).pow(3)
    private fun easeInOut(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    private fun easeOutBack(x: Float): Float { val c = 1.5f; return 1f + (c + 1f) * (x - 1f).pow(3) + c * (x - 1f).pow(2) }
    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private val loc = IntArray(2)
    private val myLoc = IntArray(2)
    private fun offsetOf(v: View): Pair<Float, Float> {
        getLocationInWindow(myLoc); v.getLocationInWindow(loc)
        return Pair((loc[0] - myLoc[0]).toFloat(), (loc[1] - myLoc[1]).toFloat())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val morph = easeInOut(seg(MORPH_START, TOTAL))

        // 背景：morph 时淡出，露出下面的主界面
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Pal.alpha(Pal.BG, 1f - morph)
        c.drawRect(0f, 0f, w, h, p)

        // 大表盘的位置
        val bigR = min(w * 0.44f, h * 0.27f)
        val bigCx = w / 2f
        val bigCy = h * 0.44f

        val g = host.gauge
        val (gx, gy) = offsetOf(g)
        val tCx = gx + g.gaugeCx
        val tCy = gy + g.gaugeCy
        val tR = g.gaugeR

        val cx = lerp(bigCx, tCx, morph)
        val cy = lerp(bigCy, tCy, morph)
        val r = lerp(bigR, tR, morph)

        drawPowerLine(c, w, bigCy)
        drawStreaks(c, w, bigCy, bigR)
        drawGantry(c, w, bigCx, bigCy, bigR)
        drawGauge(c, cx, cy, r)
        drawFlash(c, w, h)
        drawBrand(c, w, bigCy, bigR, morph)
    }

    private fun drawPowerLine(c: Canvas, w: Float, y: Float) {
        val k = seg(0f, POWER_END)
        if (k <= 0f || k >= 1f) return
        val half = w / 2f * easeOut(k)
        val a = if (k < 0.6f) 1f else 1f - (k - 0.6f) / 0.4f
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(w / 2f - half, 0f, w / 2f + half, 0f,
            intArrayOf(0, Pal.alpha(Pal.RED_HOT, a), Pal.alpha(Pal.WHITE, a), Pal.alpha(Pal.RED_HOT, a), 0),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(w / 2f - half, y - dp(1f), w / 2f + half, y + dp(1f), p)
        p.shader = RadialGradient(w / 2f, y, dp(90f), Pal.alpha(Pal.RED, a * 0.35f), 0, Shader.TileMode.CLAMP)
        c.drawRect(w / 2f - dp(90f), y - dp(90f), w / 2f + dp(90f), y + dp(90f), p)
        p.shader = null
    }

    private fun drawGantry(c: Canvas, w: Float, cx: Float, cy: Float, r: Float) {
        val inK = easeOut(seg(GANTRY_IN, GANTRY_IN + 300f))
        val outK = seg(LIGHTS_OUT + 40f, LIGHTS_OUT + 360f)
        val a = inK * (1f - outK)
        if (a <= 0.01f) return
        val scale = 1f - 0.12f * easeOut(outK)
        val colW = min(w * 0.15f, dp(64f)) * scale
        val gap = colW * 0.22f
        val total = colW * 5 + gap * 4
        val left = cx - total / 2f
        val housingH = colW * 2.05f
        val top = cy - housingH / 2f - r * 0.05f

        txt.textAlign = Paint.Align.CENTER
        txt.typeface = Pal.COND_BOLD
        txt.letterSpacing = 0.35f
        txt.textSize = dp(11f)
        txt.color = Pal.alpha(Pal.STEEL, a)
        val lit = if (t >= LIGHTS_OUT) 0 else lightsFired
        c.drawText(if (t >= LIGHTS_OUT) "LIGHTS OUT" else "START SEQUENCE", cx, top - dp(22f), txt)

        for (i in 0 until 5) {
            val x = left + i * (colW + gap)
            rect.set(x, top, x + colW, top + housingH)
            p.style = Paint.Style.FILL
            p.color = Pal.alpha(0xFF0E1013.toInt(), a)
            c.drawRoundRect(rect, colW * 0.18f, colW * 0.18f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1f)
            p.color = Pal.alpha(Pal.LINE, a)
            c.drawRoundRect(rect, colW * 0.18f, colW * 0.18f, p)
            val on = i < lit
            for (j in 0 until 2) {
                val ly = top + housingH * (0.27f + 0.46f * j)
                val lr = colW * 0.33f
                p.style = Paint.Style.FILL
                if (on) {
                    p.shader = RadialGradient(x + colW / 2f, ly, lr * 2.6f,
                        Pal.alpha(Pal.RED, a * 0.55f), 0, Shader.TileMode.CLAMP)
                    c.drawCircle(x + colW / 2f, ly, lr * 2.6f, p)
                    p.shader = RadialGradient(x + colW / 2f - lr * 0.25f, ly - lr * 0.25f, lr,
                        Pal.alpha(0xFFFFB3A8.toInt(), a), Pal.alpha(Pal.RED_HOT, a), Shader.TileMode.CLAMP)
                    c.drawCircle(x + colW / 2f, ly, lr, p)
                    p.shader = null
                } else {
                    p.color = Pal.alpha(0xFF24080A.toInt(), a)
                    c.drawCircle(x + colW / 2f, ly, lr, p)
                }
            }
        }
        txt.typeface = Pal.COND
        txt.letterSpacing = 0.2f
        txt.textSize = dp(10f)
        txt.color = Pal.alpha(Pal.STEEL_DIM, a)
        c.drawText("$lit / 5", cx, top + housingH + dp(24f), txt)
    }

    private fun drawStreaks(c: Canvas, w: Float, cy: Float, r: Float) {
        val k = seg(LIGHTS_OUT, LIGHTS_OUT + 620f)
        if (k <= 0f || k >= 1f) return
        val fade = 1f - k
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (s in streaks) {
            val travel = easeOut(k) * s[2]
            val y = cy + s[0] * r * 1.1f
            val head = w / 2f + s[3] * (dp(20f) + travel * w * 0.62f)
            val len = s[1] * w * 0.35f * (0.3f + travel)
            val tail = head - s[3] * len
            p.strokeWidth = dp(s[4])
            p.shader = LinearGradient(tail, y, head, y, 0, Pal.alpha(if (s[4] > 1.6f) Pal.WHITE else Pal.RED_HOT, fade * 0.85f), Shader.TileMode.CLAMP)
            c.drawLine(tail, y, head, y, p)
        }
        p.shader = null
    }

    private fun drawGauge(c: Canvas, cx: Float, cy: Float, r: Float) {
        val revealK = seg(REVEAL_START, REVEAL_END)
        if (revealK <= 0f) return
        val reveal = easeOut(revealK)
        val g = host.gauge
        val s = g.state
        val live = g.liveNeedle()

        val needle: Float
        val glowBoost: Float
        if (t < REVEAL_END) {
            needle = 8f * easeOut(revealK)
            glowBoost = revealK
        } else {
            val f = easeOutBack(seg(REVEAL_END, FALL_END))
            needle = lerp(8f, live, f)
            glowBoost = 1f - seg(REVEAL_END, FALL_END)
        }
        val readout = easeOut(seg(REVEAL_END - 120f, FALL_END))

        val oldGlow = s.glow
        s.glow = max(oldGlow, lerp(oldGlow, 1f, glowBoost))
        GaugeRenderer.draw(c, cx, cy, r, s, reveal = reveal, readout = readout,
            needleOverride = if (t >= FALL_END) live else needle)
        s.glow = oldGlow
    }

    private fun drawFlash(c: Canvas, w: Float, h: Float) {
        val k = seg(LIGHTS_OUT, LIGHTS_OUT + 260f)
        if (k <= 0f || k >= 1f) return
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(w / 2f, h * 0.44f, max(w, h) * 0.7f,
            Pal.alpha(Pal.RED_HOT, 0.38f * (1f - k)), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }

    private fun drawBrand(c: Canvas, w: Float, bigCy: Float, bigR: Float, morph: Float) {
        val inK = easeOut(seg(BRAND_IN, BRAND_IN + 480f))
        if (inK <= 0f) return
        val title = host.titleView
        val sub = host.subtitleView
        val tp = titlePaint ?: TextPaint(title.paint).also { titlePaint = it }
        val sp = subPaint ?: TextPaint(sub.paint).also { subPaint = it }

        drawMorphText(c, w, title, tp, title.textSize * 2.1f, bigCy + bigR * 1.30f + dp(18f) * (1f - inK), inK, morph)
        drawMorphText(c, w, sub, sp, sub.textSize * 1.7f, bigCy + bigR * 1.30f + title.textSize * 1.25f + dp(18f) * (1f - inK), inK, morph)
    }

    private fun drawMorphText(c: Canvas, w: Float, tv: TextView, paint: TextPaint, bigSize: Float, bigBaseline: Float, inK: Float, morph: Float) {
        val text = tv.text.toString()
        val (ox, oy) = offsetOf(tv)
        val lineLeft = tv.layout?.getLineLeft(0) ?: 0f
        val targetX = ox + tv.totalPaddingLeft + lineLeft
        val targetBaseline = oy + tv.totalPaddingTop + (tv.layout?.getLineBaseline(0)?.toFloat() ?: tv.baseline.toFloat())
        val targetSize = tv.textSize

        paint.textAlign = Paint.Align.LEFT
        paint.textSize = bigSize
        val bigX = w / 2f - paint.measureText(text) / 2f

        paint.textSize = lerp(bigSize, targetSize, morph)
        val x = lerp(bigX, targetX, morph)
        val y = lerp(bigBaseline, targetBaseline, morph)
        paint.color = tv.currentTextColor
        paint.alpha = (255 * inK).toInt()
        c.drawText(text, x, y, paint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
