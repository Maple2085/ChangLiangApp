package com.changliang.screenon

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 整个 App 共用的黑红色板。红色只用于信号：选中、点火、红线。 */
object Pal {
    const val BG = 0xFF050607.toInt()
    const val PANEL = 0xFF0D0F13.toInt()
    const val PANEL_HI = 0xFF161A20.toInt()
    const val LINE = 0xFF22262E.toInt()
    const val RED = 0xFFE10600.toInt()
    const val RED_HOT = 0xFFFF3B30.toInt()
    const val RED_DEEP = 0xFF5A0A08.toInt()
    const val WHITE = 0xFFF4F1EA.toInt()
    const val STEEL = 0xFF7C828C.toInt()
    const val STEEL_DIM = 0xFF3A3F47.toInt()
    const val GREEN = 0xFF3DDC84.toInt()
    const val AMBER = 0xFFFFB020.toInt()

    val COND_BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val COND: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val COND_LIGHT: Typeface = Typeface.create("sans-serif-condensed-light", Typeface.NORMAL)

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * k).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * ((color ushr 24) and 0xFF)).toInt() shl 24)
}

/** 仪表要画的内容。主界面和入场动画读的是同一份。 */
class GaugeState {
    /** 指针位置，0..8，对应刻度 0/5/10/15/20/30/1H/2H/∞ */
    var needle = 0f
    var label = "READY"
    var value = "30:00"
    var valueScale = 1f
    var sub = ""
    var mode = 1          // 0=P 1=N 2=D 3=∞
    var alert = false     // 数字转红
    var pulse = 0f        // 0..1 红色脉冲
    var glow = 0.35f      // 0..1 整体亮度
}

/**
 * 转速表。入场动画和主界面都调用这里，所以动画最后一帧和真实控件逐像素一致。
 * 角度：从左下 135° 顺时针扫 270° 到右下 45°。
 */
object GaugeRenderer {
    const val START = 135f
    const val SWEEP = 270f
    const val SEGMENTS = 8
    private const val REDLINE = 7f
    private val LABELS = arrayOf("0", "5", "10", "15", "20", "30", "1H", "2H", "∞")
    private val MODES = arrayOf("P", "N", "D", "∞")

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun angleOf(pos: Float) = START + SWEEP * (pos / SEGMENTS)

    /**
     * @param reveal 0..1 刻度/弧线展开进度（入场用，主界面恒为 1）
     * @param readout 0..1 中央读数透明度
     * @param needleOverride 入场动画里覆盖指针位置；null 用 state.needle
     */
    fun draw(
        c: Canvas, cx: Float, cy: Float, r: Float, s: GaugeState,
        reveal: Float = 1f, readout: Float = 1f, needleOverride: Float? = null
    ) {
        val glow = s.glow
        val needle = (needleOverride ?: s.needle).coerceIn(0f, SEGMENTS.toFloat())

        // 背后的红色光晕
        val haloA = (0.10f + 0.22f * glow + 0.25f * s.pulse) * reveal
        if (haloA > 0.01f) {
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(
                cx, cy, r * 1.35f,
                intArrayOf(Pal.alpha(Pal.RED, haloA), Pal.alpha(Pal.RED_DEEP, haloA * 0.35f), 0),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r * 1.35f, p)
            p.shader = null
        }

        // 表盘面
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx, cy - r * 0.3f, r * 1.1f,
            intArrayOf(Pal.alpha(0xFF14171D.toInt(), reveal), Pal.alpha(0xFF07080A.toInt(), reveal)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 1.0f, p)
        p.shader = null

        // 外圈金属框
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.018f
        p.color = Pal.alpha(0xFF2A2F37.toInt(), reveal)
        c.drawCircle(cx, cy, r * 1.0f, p)
        p.strokeWidth = r * 0.006f
        p.color = Pal.alpha(0xFF3C424C.toInt(), reveal * 0.8f)
        c.drawCircle(cx, cy, r * 0.975f, p)

        val sweepShown = SWEEP * reveal

        // 进度轨道（暗）
        rect.set(cx - r * 0.90f, cy - r * 0.90f, cx + r * 0.90f, cy + r * 0.90f)
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = r * 0.045f
        p.color = Pal.alpha(0xFF181B21.toInt(), reveal)
        c.drawArc(rect, START, sweepShown, false, p)

        // 红线区（7..8）
        val redStart = angleOf(REDLINE)
        val redEndShown = min(START + sweepShown, START + SWEEP)
        if (redEndShown > redStart) {
            p.color = Pal.alpha(Pal.RED_DEEP, reveal)
            c.drawArc(rect, redStart, redEndShown - redStart, false, p)
        }

        // 点亮的进度（到指针）
        val litSweep = min(SWEEP * (needle / SEGMENTS), sweepShown)
        if (litSweep > 0.3f) {
            p.strokeWidth = r * 0.12f
            p.color = Pal.alpha(Pal.RED, 0.10f + 0.16f * glow)
            c.drawArc(rect, START, litSweep, false, p)
            p.strokeWidth = r * 0.045f
            p.color = Pal.mix(Pal.RED, Pal.RED_HOT, glow)
            c.drawArc(rect, START, litSweep, false, p)
        }

        // 刻度
        p.strokeCap = Paint.Cap.ROUND
        val ticks = SEGMENTS * 5
        for (i in 0..ticks) {
            val pos = i / 5f
            val frac = pos / SEGMENTS
            if (frac > reveal + 0.0001f) break
            val major = i % 5 == 0
            val a = Math.toRadians(angleOf(pos).toDouble())
            val outer = r * 0.83f
            val inner = outer - if (major) r * 0.11f else r * 0.05f
            val lit = pos <= needle + 0.001f
            p.strokeWidth = if (major) r * 0.022f else r * 0.010f
            p.color = when {
                pos >= REDLINE -> if (lit) Pal.RED_HOT else Pal.RED
                lit -> if (major) Pal.WHITE else Pal.alpha(Pal.WHITE, 0.75f)
                else -> if (major) Pal.STEEL else Pal.STEEL_DIM
            }
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(cx + ca * inner, cy + sa * inner, cx + ca * outer, cy + sa * outer, p)
        }

        // 刻度数字
        text.typeface = Pal.COND_BOLD
        text.textSize = r * 0.115f
        text.letterSpacing = 0f
        val labelR = r * 0.60f
        for (i in 0..SEGMENTS) {
            val frac = i / SEGMENTS.toFloat()
            if (frac > reveal + 0.0001f) break
            val appear = ((reveal - frac) * 10f).coerceIn(0f, 1f)
            val a = Math.toRadians(angleOf(i.toFloat()).toDouble())
            val x = cx + cos(a).toFloat() * labelR
            val y = cy + sin(a).toFloat() * labelR - (text.descent() + text.ascent()) / 2f
            val lit = i <= needle + 0.001f
            text.color = Pal.alpha(
                when {
                    i == SEGMENTS -> Pal.RED_HOT
                    i >= REDLINE -> if (lit) Pal.RED_HOT else Pal.RED
                    lit -> Pal.WHITE
                    else -> Pal.STEEL
                }, appear
            )
            c.drawText(LABELS[i], x, y, text)
        }

        // 指针：只画外圈一段，把中央留给数字
        if (reveal > 0.02f) {
            val a = Math.toRadians(angleOf(min(needle, SEGMENTS * reveal)).toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val r0 = r * 0.48f; val r1 = r * 0.96f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = r * 0.075f
            p.color = Pal.alpha(Pal.RED, 0.18f + 0.22f * glow)
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, p)
            p.strokeWidth = r * 0.030f
            p.color = Pal.RED_HOT
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, p)
            p.strokeWidth = r * 0.009f
            p.color = Pal.WHITE
            c.drawLine(cx + ca * (r0 + r * 0.04f), cy + sa * (r0 + r * 0.04f), cx + ca * (r1 - r * 0.02f), cy + sa * (r1 - r * 0.02f), p)
        }

        // 内圈细框
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.006f
        p.color = Pal.alpha(0xFF2A2F37.toInt(), reveal)
        c.drawCircle(cx, cy, r * 0.45f, p)

        if (readout <= 0.01f) return

        // 中央读数
        text.typeface = Pal.COND_BOLD
        text.letterSpacing = 0.22f
        text.textSize = r * 0.075f
        text.color = Pal.alpha(if (s.alert) Pal.RED_HOT else Pal.STEEL, readout)
        c.drawText(s.label, cx, cy - r * 0.22f, text)

        text.letterSpacing = 0f
        text.typeface = if (s.value == "∞") Pal.COND else Pal.COND_LIGHT
        text.textSize = r * 0.30f * s.valueScale * (if (s.value == "∞") 1.25f else 1f)
        val valueColor = if (s.alert) Pal.mix(Pal.RED_HOT, Pal.WHITE, 0.25f * (1f - s.pulse)) else Pal.WHITE
        text.color = Pal.alpha(valueColor, readout)
        val vy = cy + r * 0.10f + if (s.value == "∞") r * 0.02f else 0f
        c.drawText(s.value, cx, vy, text)

        text.typeface = Pal.COND
        text.letterSpacing = 0.08f
        text.textSize = r * 0.068f
        text.color = Pal.alpha(Pal.STEEL, readout)
        c.drawText(s.sub, cx, cy + r * 0.27f, text)

        // 底部 P N D ∞
        text.typeface = Pal.COND_BOLD
        text.letterSpacing = 0f
        text.textSize = r * 0.085f
        val gap = r * 0.13f
        val baseY = cy + r * 0.62f
        for (i in MODES.indices) {
            val x = cx + (i - 1.5f) * gap
            val on = i == s.mode
            text.color = Pal.alpha(if (on) (if (i == 0) Pal.AMBER else Pal.RED_HOT) else Pal.STEEL_DIM, readout)
            c.drawText(MODES[i], x, baseY, text)
            if (on) {
                p.style = Paint.Style.FILL
                p.color = text.color
                c.drawRect(x - r * 0.035f, baseY + r * 0.03f, x + r * 0.035f, baseY + r * 0.042f, p)
            }
        }
        p.style = Paint.Style.STROKE
    }
}
