package com.changliang.screenon

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun View.dp(v: Float) = v * resources.displayMetrics.density

/**
 * 挡位条：5 10 15 20 30 1H 2H ∞ + 自定义（SET）。可点也可拖，像拨挡。
 * 最后一格显示自定义值；还没设过时显示「+」，选中它会请求打开调表板。
 */
class GearStripView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object { const val CUSTOM = 8 }

    private val labels = arrayOf("5", "10", "15", "20", "30", "1H", "2H", "∞", "+")
    private val subs = arrayOf("MIN", "MIN", "MIN", "MIN", "MIN", "HR", "HR", "MAX", "SET")
    var onPick: ((Int) -> Unit)? = null
    /** 点按（或长按）自定义格：由 Activity 决定是选中还是打开调表板 */
    var onCustomTap: ((alreadySelected: Boolean) -> Unit)? = null
    var selected = 4; private set
    private var indicator = 4f
    private var indAnim: ValueAnimator? = null
    var customMinutes = 0; private set
    var locked = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private var downIndex = -1
    private var downSelected = -1
    private var moved = false
    private var longFired = false
    private val longPress = Runnable {
        if (downIndex == CUSTOM && !moved && !locked) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onCustomTap?.invoke(true)
        }
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), dp(64f).toInt())

    /** 设置自定义格的显示；minutes<=0 表示还没设 */
    fun setCustom(minutes: Int) {
        customMinutes = minutes
        labels[CUSTOM] = when {
            minutes <= 0 -> "+"
            minutes < 60 -> "$minutes"
            minutes % 60 == 0 -> "${minutes / 60}H"
            minutes % 30 == 0 -> "${minutes / 60}.5H"
            else -> "${minutes / 60}:${String.format(java.util.Locale.US, "%02d", minutes % 60)}"
        }
        updateDesc()
        invalidate()
    }

    fun setSelected(index: Int, animate: Boolean) {
        selected = index.coerceIn(0, labels.lastIndex)
        updateDesc()
        indAnim?.cancel()
        if (!animate) { indicator = selected.toFloat(); invalidate(); return }
        indAnim = ValueAnimator.ofFloat(indicator, selected.toFloat()).apply {
            duration = 260L
            interpolator = PathInterpolator(0.2f, 0.9f, 0.25f, 1.05f)
            addUpdateListener { indicator = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun updateDesc() {
        contentDescription = if (selected == CUSTOM) "常亮时长 自定义 ${labels[CUSTOM]}"
        else "常亮时长 ${labels[selected]}${subs[selected]}"
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cw = w / labels.size
        val alphaAll = if (locked) 0.42f else 1f

        rect.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.color = Pal.PANEL
        c.drawRoundRect(rect, dp(14f), dp(14f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1f)
        p.color = Pal.LINE
        c.drawRoundRect(rect, dp(14f), dp(14f), p)
        for (i in 1 until labels.size) {
            val x = cw * i
            if (i == CUSTOM) {
                // 自定义格前用一条更长的分隔，表示它是另一类
                p.color = Pal.STEEL_DIM
                c.drawLine(x, h * 0.18f, x, h * 0.82f, p)
                p.color = Pal.LINE
            } else {
                c.drawLine(x, h * 0.32f, x, h * 0.68f, p)
            }
        }

        // 滑块
        val l = cw * indicator + dp(3f)
        rect.set(l, dp(3f), l + cw - dp(6f), h - dp(3f))
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            Pal.alpha(Pal.RED_HOT, alphaAll), Pal.alpha(0xFFB00400.toInt(), alphaAll), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, dp(11f), dp(11f), p)
        p.shader = null
        p.color = Pal.alpha(0x66FFFFFF, alphaAll)
        c.drawRect(rect.left + dp(8f), rect.top + dp(2f), rect.right - dp(8f), rect.top + dp(3f), p)

        val maxW = cw - dp(6f)
        for (i in labels.indices) {
            val x = cw * i + cw / 2f
            val near = (1f - abs(indicator - i)).coerceIn(0f, 1f)
            t.typeface = Pal.COND_BOLD
            t.letterSpacing = 0f
            t.textSize = dp(16f) + dp(2f) * near
            val tw = t.measureText(labels[i])
            if (tw > maxW) t.textSize *= maxW / tw
            val base = when (i) {
                7 -> Pal.RED_HOT
                CUSTOM -> if (customMinutes > 120) Pal.RED_HOT else Pal.AMBER
                else -> Pal.STEEL
            }
            t.color = Pal.alpha(Pal.mix(base, Pal.WHITE, near), alphaAll)
            c.drawText(labels[i], x, h * 0.53f, t)
            t.typeface = Pal.COND
            t.letterSpacing = 0.12f
            t.textSize = dp(8.5f)
            t.color = Pal.alpha(Pal.mix(Pal.STEEL_DIM, 0xCCFFFFFF.toInt(), near), alphaAll)
            c.drawText(subs[i], x, h * 0.78f, t)
        }
    }

    private fun cellAt(x: Float) = (x / (width / labels.size.toFloat())).toInt().coerceIn(0, labels.lastIndex)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked || !isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downIndex = cellAt(e.x)
                downSelected = selected
                moved = false
                longFired = false
                if (downIndex == CUSTOM) {
                    postDelayed(longPress, 450L)
                } else if (downIndex != selected) {
                    choose(downIndex)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val i = cellAt(e.x)
                if (i != downIndex) { moved = true; removeCallbacks(longPress) }
                // 拖动只在预设挡之间换挡；拖到自定义格且已设过值时也换过去
                if (moved && i != selected && (i != CUSTOM || customMinutes > 0)) choose(i)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!moved && !longFired && downIndex == CUSTOM) {
                    onCustomTap?.invoke(downSelected == CUSTOM)
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); return true }
        }
        return true
    }

    private fun choose(i: Int) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        setSelected(i, true)
        onPick?.invoke(i)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** 点火键。∞ 档需要长按，防误触后一直亮屏。 */
class IgnitionButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { IGNITE, HOLD, STOP, PREFLIGHT }

    var mode = Mode.IGNITE
        set(v) { if (field != v) { field = v; cancelHold(false); invalidate() } }
    var title = "IGNITION"
        set(v) { field = v; invalidate() }
    var subtitle = "点火"
        set(v) { field = v; invalidate() }
    var onTrigger: (() -> Unit)? = null
    var onHoldCancel: (() -> Unit)? = null

    private var hold = 0f
    private var holdAnim: ValueAnimator? = null
    private var holdDone = false
    /** 本次手势按下时的模式 */
    private var gestureMode: Mode? = null
    private val rumble = HoldRumble(context)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    init { isClickable = true; isFocusable = true }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), dp(66f).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = dp(16f)
        rect.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
        val filled = mode == Mode.IGNITE || mode == Mode.PREFLIGHT

        p.style = Paint.Style.FILL
        if (filled) {
            p.shader = LinearGradient(0f, 0f, 0f, h, Pal.RED_HOT, 0xFFB80500.toInt(), Shader.TileMode.CLAMP)
        } else {
            p.color = Pal.PANEL
        }
        if (mode == Mode.PREFLIGHT) {
            p.shader = LinearGradient(0f, 0f, 0f, h, 0xFF2A2D33.toInt(), 0xFF1A1C21.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, rad, rad, p)
        p.shader = null

        // 长按进度：从左往右灌红
        if (mode == Mode.HOLD && hold > 0f) {
            c.save()
            c.clipRect(rect.left, rect.top, rect.left + rect.width() * hold, rect.bottom)
            p.shader = LinearGradient(0f, 0f, 0f, h, Pal.RED_HOT, 0xFFB80500.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rad, rad, p)
            p.shader = null
            c.restore()
        }

        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = when (mode) {
            Mode.IGNITE -> 0x55FFFFFF
            Mode.PREFLIGHT -> Pal.AMBER
            else -> Pal.RED
        }
        c.drawRoundRect(rect, rad, rad, p)
        if (filled && mode == Mode.IGNITE) {
            p.style = Paint.Style.FILL
            p.color = 0x40FFFFFF
            c.drawRect(rect.left + rad, rect.top + dp(1.5f), rect.right - rad, rect.top + dp(2.5f), p)
        }

        // 图标
        val ic = dp(18f); val ix = dp(26f); val iy = h / 2f
        p.style = Paint.Style.FILL
        p.color = when (mode) {
            Mode.IGNITE -> Pal.WHITE
            Mode.PREFLIGHT -> Pal.AMBER
            else -> if (hold > 0.5f) Pal.WHITE else Pal.RED_HOT
        }
        path.reset()
        when (mode) {
            Mode.STOP -> c.drawRoundRect(ix, iy - ic / 2.4f, ix + ic / 1.2f, iy + ic / 2.4f, dp(2f), dp(2f), p)
            Mode.PREFLIGHT -> {
                c.drawRoundRect(ix + ic * 0.32f, iy - ic * 0.5f, ix + ic * 0.52f, iy + ic * 0.15f, dp(1.5f), dp(1.5f), p)
                c.drawCircle(ix + ic * 0.42f, iy + ic * 0.42f, ic * 0.12f, p)
            }
            else -> {
                path.moveTo(ix, iy - ic / 2f); path.lineTo(ix + ic * 0.85f, iy); path.lineTo(ix, iy + ic / 2f); path.close()
                c.drawPath(path, p)
            }
        }

        t.textAlign = Paint.Align.CENTER
        t.typeface = Pal.COND_BOLD
        t.letterSpacing = 0.16f
        t.textSize = dp(19f)
        t.color = when (mode) {
            Mode.IGNITE -> Pal.WHITE
            Mode.PREFLIGHT -> Pal.WHITE
            else -> if (hold > 0.5f) Pal.WHITE else Pal.RED_HOT
        }
        c.drawText(title, w / 2f + dp(8f), h * 0.47f, t)
        t.typeface = Pal.COND
        t.letterSpacing = 0.06f
        t.textSize = dp(11f)
        t.color = when (mode) {
            Mode.IGNITE -> 0xD9FFFFFF.toInt()
            else -> Pal.STEEL
        }
        c.drawText(subtitle, w / 2f + dp(8f), h * 0.74f, t)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureMode = mode
                animate().scaleX(0.975f).scaleY(0.975f).setDuration(90L).start()
                if (mode == Mode.HOLD) startHold()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                val inside = e.actionMasked == MotionEvent.ACTION_UP &&
                    e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                val started = gestureMode
                gestureMode = null
                if (started == Mode.HOLD) {
                    // 按住点火：触发发生在按住期间，松手只负责取消未完成的按住，
                    // 绝不能当成一次点击——此时按钮可能已经变成「熄火」
                    if (!holdDone) cancelHold(inside)
                } else if (started != mode) {
                    // 按下后模式变了（例如刚点着），这次抬起作废
                } else if (inside) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTrigger?.invoke()
                }
                return true
            }
        }
        return true
    }

    /** 无障碍双击：直接触发，不要求长按 */
    override fun performClick(): Boolean {
        super.performClick()
        onTrigger?.invoke()
        return true
    }

    override fun onDetachedFromWindow() {
        holdAnim?.cancel()
        rumble.stop()
        super.onDetachedFromWindow()
    }

    private fun startHold() {
        holdDone = false
        holdAnim?.cancel()
        val dur = ((1f - hold) * 820f).toLong().coerceAtLeast(1L)
        rumble.start(hold, dur)
        holdAnim = ValueAnimator.ofFloat(hold, 1f).apply {
            duration = dur
            interpolator = PathInterpolator(0.4f, 0f, 0.8f, 1f)
            var cancelled = false
            addUpdateListener { hold = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    holdDone = true
                    rumble.finish()
                    onTrigger?.invoke()
                    hold = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    private fun cancelHold(notify: Boolean) {
        if (holdAnim?.isRunning == true && !holdDone) rumble.stop()
        holdAnim?.cancel()
        if (hold <= 0f) { if (notify) onHoldCancel?.invoke(); return }
        holdAnim = ValueAnimator.ofFloat(hold, 0f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { hold = it.animatedValue as Float; invalidate() }
            start()
        }
        if (notify) onHoldCancel?.invoke()
    }
}

/** 红色拨杆开关 */
class ToggleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var checked = false; private set
    private var pos = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var onChange: ((Boolean) -> Unit)? = null

    init { isClickable = true; isFocusable = true }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(50f).toInt(), dp(30f).toInt())

    fun setChecked(v: Boolean, animate: Boolean) {
        checked = v
        contentDescription = if (v) "已开启" else "已关闭"
        if (!animate) { pos = if (v) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 200L
            interpolator = PathInterpolator(0.3f, 0f, 0.2f, 1f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        setChecked(!checked, true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onChange?.invoke(checked)
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.color = Pal.mix(0xFF22252B.toInt(), Pal.RED, pos)
        c.drawRoundRect(rect, h / 2f, h / 2f, p)
        val kr = h / 2f - dp(3f)
        val kx = h / 2f + (w - h) * pos
        p.color = Pal.mix(Pal.STEEL, Pal.WHITE, pos)
        c.drawCircle(kx, h / 2f, kr, p)
    }
}

/** 背景：近黑 + 极淡斜纹碳纤维 + 跟随表盘的红光 + 边缘暗角 */
class CarbonBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val tile: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 8) for (x in 0 until 8) {
            val band = ((x + y) / 2) % 2 == 0
            val v = if (band) 0xFF08090B.toInt() else 0xFF050607.toInt()
            setPixel(x, y, v)
        }
    }
    private val texPaint = Paint().apply {
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowX = -1f
    private var glowY = -1f

    fun setGlowCenter(x: Float, y: Float) {
        if (abs(x - glowX) < 0.5f && abs(y - glowY) < 0.5f) return
        glowX = x; glowY = y
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rebuild() }

    private fun rebuild() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val gx = if (glowX < 0f) w / 2f else glowX
        val gy = if (glowY < 0f) h * 0.36f else glowY
        glowPaint.shader = RadialGradient(gx, gy, max(w, h) * 0.62f,
            intArrayOf(0x2EE10600, 0x0FE10600, 0x00000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        vignette.shader = RadialGradient(w / 2f, h * 0.45f, max(w, h) * 0.78f,
            intArrayOf(0x00000000, 0x00000000, 0xCC000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, texPaint)
        c.drawRect(0f, 0f, w, h, glowPaint)
        c.drawRect(0f, 0f, w, h, vignette)
    }
}
