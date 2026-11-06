package com.changliang.screenon

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.pow

/**
 * 按住点火时的「拉转速」震动：脉冲越来越密、越来越重，按满时一记最强的短震收尾。
 * 马达支持振幅调节时用振幅渐强；不支持时用越来越长的脉冲模拟。
 */
class HoldRumble(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val amplitude = vibrator?.hasAmplitudeControl() == true

    /** 从进度 from（0..1）开始，在 durationMs 内渐强到满 */
    fun start(from: Float, durationMs: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || durationMs < 40L) return
        val timings = ArrayList<Long>()
        val amps = ArrayList<Int>()
        var elapsed = 0L
        while (elapsed < durationMs) {
            val p = from + (1f - from) * (elapsed.toFloat() / durationMs)
            // 周期 95ms → 28ms，越来越密
            val period = (95f - 67f * p.pow(1.4f)).toLong()
            if (amplitude) {
                val on = (period * (0.40f + 0.30f * p)).toLong().coerceAtLeast(8L)
                timings += on; amps += (35 + 220 * p.pow(1.7f)).toInt().coerceIn(1, 255)
                timings += period - on; amps += 0
            } else {
                // 没有振幅：靠脉冲变长来「变重」
                val on = (6f + 26f * p.pow(1.5f)).toLong()
                timings += on; amps += 255
                timings += (period - on).coerceAtLeast(10L); amps += 0
            }
            elapsed += period
        }
        play(VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), -1))
    }

    /** 按满：一记最重的短震 */
    fun finish() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        play(
            if (amplitude) VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 30), intArrayOf(0, 255, 0, 180), -1)
            else VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    fun stop() { vibrator?.cancel() }

    private fun play(effect: VibrationEffect) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 按触摸反馈归类：用户关掉「触摸振动」时一并静音
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
        } catch (_: Exception) {
        }
    }
}
