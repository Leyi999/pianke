package com.heleyi.fitnesstimer

import android.content.res.Resources
import java.util.Locale

object TimeFormat {

    /** 向上取整到秒：剩余 89.2 秒显示 90 秒（01:30）。 */
    fun ceilSeconds(ms: Long): Long = if (ms <= 0L) 0L else (ms + 999L) / 1000L

    /** MM:SS，例如 90 → "01:30"，3600 → "60:00"。 */
    fun mmss(totalSeconds: Long): String =
        String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)

    /** 计时文字：训练中为本组已用时；休息为剩余时间（向上取整）；超时为 “+多休息的时长”。 */
    fun phaseTimer(w: Workout, now: Long): String {
        if (!w.state.resting) return mmss(w.phaseElapsedMs(now) / 1000L)
        val remaining = w.restRemainingMs(now)
        return if (remaining > 0L) mmss(ceilSeconds(remaining)) else "+" + mmss(-remaining / 1000L)
    }

    /** 去掉分钟的前导零：“01:30” → “1:30”，“+00:12” → “+0:12”；位数少，同样的宽度里数字能画得更大。 */
    fun compact(timer: String): String = timer.replaceFirst(LEADING_ZERO, "$1")

    private val LEADING_ZERO = Regex("""^(\+?)0(?=\d:)""")

    /** HH:MM:SS，用于总用时。 */
    fun hhmmss(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    /** 设置页用的易读时长：“45秒”“1分30秒”“2分钟”“1小时5分钟”。 */
    fun human(res: Resources, seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return when {
            h > 0 && m > 0 -> res.getString(R.string.duration_hm, h, m)
            h > 0 -> res.getString(R.string.duration_h, h)
            m > 0 && s > 0 -> res.getString(R.string.duration_ms, m, s)
            m > 0 -> res.getString(R.string.duration_m, m)
            else -> res.getString(R.string.duration_s, s)
        }
    }
}
