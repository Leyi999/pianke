package com.heleyi.fitnesstimer

import android.content.Context
import android.icu.util.Calendar
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * 训练记录的文字：记录页的卡片和详情、首页的“上次训练”共用。
 * “今天 / 昨天”按创建时的日期算，每次刷新界面时新建一个。
 */
class RecordFormat(context: Context) {

    private val res = context.resources
    private val timeFormat = DateFormat.getTimeFormat(context) // 跟随系统的 12 / 24 小时制
    private val weekdays = res.getStringArray(R.array.weekdays)
    val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)

    fun date(r: WorkoutRecord): LocalDate = Instant.ofEpochMilli(r.startedAt).atZone(zone).toLocalDate()

    fun time(wallMs: Long): String = timeFormat.format(Date(wallMs))

    /** 今天 / 昨天 / 周几。 */
    fun weekday(date: LocalDate): String = when (date) {
        today -> res.getString(R.string.history_today)
        today.minusDays(1) -> res.getString(R.string.history_yesterday)
        else -> dayOfWeek(date)
    }

    /** 周几。 */
    fun dayOfWeek(date: LocalDate): String = weekdays[date.dayOfWeek.value - 1]

    /** 今天 / 昨天 / 10月21日。 */
    fun relativeDay(date: LocalDate): String =
        if (date == today || date == today.minusDays(1)) weekday(date) else monthDay(date)

    fun monthDay(date: LocalDate): String = res.getString(R.string.history_date, date.monthValue, date.dayOfMonth)

    /** 部位：“胸、肩”；按 [BodyPart] 的顺序。 */
    fun parts(parts: Set<BodyPart>): String =
        parts.sortedBy { it.ordinal }.joinToString(res.getString(R.string.part_separator)) { res.getString(it.label) }

    /** “胸、肩 · 4 个动作 · 15 组”；没选部位时只有动作数和组数。 */
    fun summary(r: WorkoutRecord): String = if (r.parts.isEmpty()) {
        res.getString(R.string.history_summary, r.exercises, r.sets)
    } else {
        res.getString(R.string.history_summary_parts, parts(r.parts), r.exercises, r.sets)
    }

    /** 每个动作的组数：“4 · 4 · 3”；没有明细时为 null。 */
    fun setsPerExercise(r: WorkoutRecord): String? = r.setsPerExercise.takeIf { it.isNotEmpty() }
        ?.joinToString(res.getString(R.string.record_per_exercise_separator))

    /** 读屏和删除确认框用的完整描述：日期 星期 开始时间，部位，动作数，组数，用时，备注。 */
    fun description(r: WorkoutRecord): String {
        val date = date(r)
        val segments = listOfNotNull(
            res.getString(R.string.cd_record_when, monthDay(date), weekday(date), time(r.startedAt)),
            parts(r.parts).takeIf { it.isNotEmpty() },
            res.getString(R.string.cd_record_exercises, r.exercises),
            res.getString(R.string.cd_record_sets, r.sets),
            res.getString(R.string.cd_record_duration, TimeFormat.workoutDuration(res, r.durationMs)),
            r.note.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.cd_record_note, it) },
        )
        return segments.joinToString(res.getString(R.string.cd_record_separator))
    }

    companion object {
        /** 一周从哪天开始，跟随系统设置（中文系统是周一）。 */
        fun firstDayOfWeek(): DayOfWeek = DayOfWeek.SUNDAY.plus(Calendar.getInstance().firstDayOfWeek - 1L)
    }
}
