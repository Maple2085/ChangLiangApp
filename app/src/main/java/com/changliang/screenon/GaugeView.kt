package com.changliang.screenon

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.min
import kotlin.math.sin

/** 主界面的转速表。绘制全部交给 GaugeRenderer，入场动画也用同一个。 */
class GaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    val state = GaugeState()

    private var needleTarget = 0f
    private var needleShown = 0f
    private var needleAnim: ValueAnimator? = null
    private var flutter = false
    private var pulsing = false
    private var hostResumed = true
    private var loopRunning = false

    /** 表盘中心和半径（本 View 坐标），入场动画据此对位 */
    val gaugeCx get() = width / 2f
    val gaugeCy get() = height / 2f
    val gaugeR get() = min(width, height) / 2f * 0.90f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = (resources.displayMetrics.heightPixels * 0.46f).toInt()
        val side = min(w, maxH)
        setMeasuredDimension(w, side)
    }

    /** 设置目标指针。animate=false 时立即到位。 */
    fun setNeedle(target: Float, animate: Boolean) {
        if (target == needleTarget && (needleAnim?.isRunning == true || needleShown == target)) return
        needleTarget = target
        if (!animate) {
            needleAnim?.cancel()
            needleShown = target
            invalidate()
            return
        }
        if (needleAnim?.isRunning == true) {
            // 正在扫表：结束时会落到新的目标
            return
        }
        if (kotlin.math.abs(target - needleShown) < 0.04f) {
            needleShown = target
            invalidate()
            return
        }
        runNeedle(needleShown, target, 520L, DecelerateInterpolator(1.6f))
    }

    /** 每次打开时的轻量自检：从 0 扫到目标 */
    fun sweepIn() {
        needleShown = 0f
        runNeedle(0f, needleTarget, 900L, OvershootInterpolator(0.9f), delay = 120L)
    }

    /** 点火瞬间轰一下油门再落回 */
    fun blip() {
        val from = needleShown
        val peak = min(8f, from + 1.6f)
        needleAnim?.cancel()
        needleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            addUpdateListener {
                val t = it.animatedFraction
                val up = if (t < 0.3f) t / 0.3f else 1f - (t - 0.3f) / 0.7f
                val base = from + (needleTarget - from) * t
                needleShown = base + (peak - from) * (up * up)
                state.glow = 0.55f + 0.45f * up
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { needleShown = needleTarget }
            })
            start()
        }
    }

    private fun runNeedle(from: Float, to: Float, dur: Long, interp: android.animation.TimeInterpolator, delay: Long = 0L) {
        needleAnim?.cancel()
        needleAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = dur
            startDelay = delay
            interpolator = interp
            var cancelled = false
            addUpdateListener { needleShown = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    needleShown = needleTarget
                    if (needleShown != to) runNeedle(to, needleTarget, 360L, DecelerateInterpolator())
                    invalidate()
                }
            })
            start()
        }
    }

    fun setEffects(flutter: Boolean, pulse: Boolean) {
        if (this.flutter == flutter && this.pulsing == pulse) return
        this.flutter = flutter
        this.pulsing = pulse
        if (!pulse) state.pulse = 0f
        ensureLoop()
        invalidate()
    }

    fun setHostResumed(resumed: Boolean) {
        hostResumed = resumed
        ensureLoop()
    }

    private val loop = object : Runnable {
        override fun run() {
            loopRunning = false
            if (!shouldLoop()) return
            invalidate()
            loopRunning = true
            postOnAnimation(this)
        }
    }

    private fun shouldLoop() = (flutter || pulsing) && hostResumed && isAttachedToWindow && windowVisibility == VISIBLE

    private fun ensureLoop() {
        if (shouldLoop() && !loopRunning) {
            loopRunning = true
            postOnAnimation(loop)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ensureLoop() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); ensureLoop() }
    override fun onDetachedFromWindow() {
        needleAnim?.cancel()
        removeCallbacks(loop)
        loopRunning = false
        super.onDetachedFromWindow()
    }

    /** 当前帧的指针（含抖动）。入场动画末段读这个值，保证交接无跳变。 */
    fun liveNeedle(): Float {
        var n = needleShown
        if (flutter) {
            val t = SystemClock.uptimeMillis() / 1000.0
            n += (0.05 * sin(t * 23.0) + 0.03 * sin(t * 37.0 + 1.3)).toFloat()
        }
        if (pulsing) {
            val t = SystemClock.uptimeMillis() / 1000.0
            state.pulse = ((sin(t * Math.PI * 2 / 1.1) + 1.0) / 2.0).toFloat()
        }
        return n.coerceIn(0f, 8f)
    }

    override fun onDraw(canvas: Canvas) {
        GaugeRenderer.draw(canvas, gaugeCx, gaugeCy, gaugeR, state, needleOverride = liveNeedle())
    }
}
