package com.heleyi.fitnesstimer

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * 训练记录页顶部的月历（布局见 view_history_calendar）：练过的日子是白色实心圆，今天是空心圆圈，
 * 前后月份的日子灰显，看得出最近几天练没练。标题下是当月训练次数和组数。
 * 箭头或左右滑动切换月份，最早到第一条记录所在的月份，最晚到本月；不在本月时点年月回到本月。
 * 点练过的日子交给 [onDayClick]。一周从哪天开始跟随系统设置。
 */
class TrainingCalendar(root: MonthSwipeLayout, private val onDayClick: (LocalDate) -> Unit) {

    private val context = root.context
    private val res = root.resources
    private val heading: View = root.findViewById(R.id.calendarHeading)
    private val title: TextView = root.findViewById(R.id.calendarTitle)
    private val summary: TextView = root.findViewById(R.id.calendarSummary)
    private val prev: View = root.findViewById(R.id.calendarPrev)
    private val next: View = root.findViewById(R.id.calendarNext)
    private val grid: LinearLayout = root.findViewById(R.id.calendarGrid)
    private val firstDayOfWeek: DayOfWeek = RecordFormat.firstDayOfWeek()
    private val weeks = ArrayList<View>(MAX_WEEKS)
    private val days = ArrayList<TextView>(MAX_WEEKS * 7)
    private val dayAccessibility = ActionLabels(AccessibilityNodeInfo.ACTION_CLICK to res.getString(R.string.calendar_show_day))
    private val regular = Typeface.create(Typeface.DEFAULT, 400, false)
    private val bold = Typeface.create(Typeface.DEFAULT, 600, false)

    /** 日期圆圈的直径：字号调大时跟着变大，但不超出格子宽度（窄窗口里反而缩小）。 */
    private val circlePx: Int

    private var zone: ZoneId = ZoneId.systemDefault()
    private var byDate: Map<LocalDate, List<WorkoutRecord>> = emptyMap()
    private var month: YearMonth = YearMonth.now(zone)
    private var minMonth: YearMonth = month
    private var maxMonth: YearMonth = month

    init {
        val density = res.displayMetrics.density
        val textPx = TextView(context, null, 0, R.style.CalendarDay).textSize
        val cellWidthPx = (res.configuration.screenWidthDp - HORIZONTAL_INSETS_DP) / 7f * density
        circlePx = (textPx * CIRCLE_PER_TEXT)
            .coerceAtLeast(MIN_CIRCLE_DP * density)
            .coerceAtMost(cellWidthPx - CIRCLE_GAP_DP * density)
            .toInt()
        val weekHeight = maxOf((WEEK_HEIGHT_DP * density).toInt(), circlePx + (CIRCLE_GAP_DP * density).toInt())

        val labels = res.getStringArray(R.array.weekdays_short) // 从周一开始
        val weekdays = root.findViewById<LinearLayout>(R.id.calendarWeekdays)
        for (i in 0L until 7L) {
            val label = TextView(context, null, 0, R.style.CalendarWeekday)
            label.text = labels[firstDayOfWeek.plus(i).value - 1]
            weekdays.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        repeat(MAX_WEEKS) {
            val week = LinearLayout(context)
            grid.addView(week, ViewGroup.LayoutParams.MATCH_PARENT, weekHeight)
            weeks.add(week)
            repeat(7) {
                val day = TextView(context, null, 0, R.style.CalendarDay)
                day.accessibilityDelegate = dayAccessibility
                week.addView(day, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                days.add(day)
            }
        }
        prev.setOnClickListener { go(month.minusMonths(1)) }
        next.setOnClickListener { go(month.plusMonths(1)) }
        root.onSwipe = { toNext -> go(if (toNext) month.plusMonths(1) else month.minusMonths(1)) }
        heading.setOnClickListener { go(YearMonth.now(zone)) }
        heading.accessibilityDelegate = ActionLabels(AccessibilityNodeInfo.ACTION_CLICK to res.getString(R.string.calendar_this_month))
    }

    /** @param records 全部记录（[History.load]）；仍显示原来的月份（超出范围时收回到范围内）。 */
    fun submit(records: List<WorkoutRecord>) {
        zone = ZoneId.systemDefault()
        byDate = records.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        val now = YearMonth.now(zone)
        minMonth = byDate.keys.minOrNull()?.let { minOf(YearMonth.from(it), now) } ?: now
        maxMonth = byDate.keys.maxOrNull()?.let { maxOf(YearMonth.from(it), now) } ?: now
        show(month)
    }

    /** 切换到 [target]（会收回到可显示的范围内），日期格子从切换的方向滑进来。 */
    private fun go(target: YearMonth) {
        val from = month
        show(target)
        if (month == from) return
        grid.translationX = grid.width * SLIDE_FRACTION * (if (month > from) 1 else -1)
        grid.alpha = 0f
        grid.animate().translationX(0f).alpha(1f).setDuration(SLIDE_MS).start()
    }

    private fun show(target: YearMonth) {
        month = target.coerceIn(minMonth, maxMonth)
        title.text = res.getString(R.string.history_month, month.year, month.monthValue)
        val inMonth = byDate.filterKeys { YearMonth.from(it) == month }.values.flatten()
        summary.text = if (inMonth.isEmpty()) {
            res.getString(R.string.calendar_none)
        } else {
            res.getString(R.string.calendar_summary, inMonth.size, inMonth.sumOf { it.sets })
        }
        prev.isEnabled = month > minMonth
        next.isEnabled = month < maxMonth
        heading.isClickable = month != YearMonth.now(zone) // 在本月时点年月没有用

        // 第一行从 1 号所在那周的第一天开始，只显示用得到的行数（4 ~ 6 行）
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val rows = (lead + month.lengthOfMonth() + 6) / 7
        val start = first.minusDays(lead.toLong())
        val today = LocalDate.now(zone)
        weeks.forEachIndexed { i, week -> week.visibility = if (i < rows) View.VISIBLE else View.GONE }
        days.forEachIndexed { i, day -> bindDay(day, start.plusDays(i.toLong()), today) }
    }

    private fun bindDay(view: TextView, date: LocalDate, today: LocalDate) {
        val inMonth = YearMonth.from(date) == month
        val records = byDate[date]
        view.text = String.format(Locale.ROOT, "%d", date.dayOfMonth)
        if (records != null) {
            view.background = circle(if (inMonth) R.drawable.bg_calendar_trained else R.drawable.bg_calendar_trained_other)
            view.setTextColor(context.getColor(if (inMonth) R.color.on_accent else R.color.text))
            view.typeface = bold
            view.contentDescription = res.getString(R.string.cd_calendar_day, date.monthValue, date.dayOfMonth, records.size)
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            view.setOnClickListener { onDayClick(date) }
        } else {
            // 没练的日子只是背景，读屏跳过，只读练过的日子
            view.background = if (date == today) circle(R.drawable.bg_calendar_today) else null
            view.setTextColor(context.getColor(if (inMonth) R.color.text else R.color.text_mute))
            view.typeface = if (date == today) bold else regular
            view.contentDescription = null
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            view.setOnClickListener(null)
            view.isClickable = false // setOnClickListener 总会把 clickable 设成 true
        }
    }

    /** 圆圈背景（drawable 里写的是默认尺寸），换成按字号算出的直径。 */
    private fun circle(id: Int): Drawable =
        (context.getDrawable(id)!!.mutate() as LayerDrawable).apply { setLayerSize(0, circlePx, circlePx) }

    private companion object {
        const val MAX_WEEKS = 6
        const val WEEK_HEIGHT_DP = 40f

        /** 圆圈直径 ≈ 字号的这个倍数，最小 [MIN_CIRCLE_DP]。 */
        const val CIRCLE_PER_TEXT = 1.9f
        const val MIN_CIRCLE_DP = 34f

        /** 相邻圆圈之间至少留的空隙。 */
        const val CIRCLE_GAP_DP = 6f

        /** 格子左右的留白：列表 20dp × 2 + 卡片 12dp × 2。 */
        const val HORIZONTAL_INSETS_DP = 64

        const val SLIDE_FRACTION = 0.15f
        const val SLIDE_MS = 180L
    }
}
