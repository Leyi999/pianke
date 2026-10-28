package com.heleyi.fitnesstimer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * 训练记录：顶部是月历（[TrainingCalendar]），下面按月分组列出每次训练，最新的在前。
 * 点一条看详情（[RecordDialog]，可改部位、备注），长按删除。从未开始界面右上角的“记录”进入。
 */
class HistoryActivity : Activity() {

    private lateinit var history: History
    private lateinit var list: ListView
    private lateinit var calendar: TrainingCalendar
    private lateinit var adapter: HistoryAdapter
    private var dialog: AlertDialog? = null

    /** 月历上点了某天后，等列表滚完让那张卡片闪一下。 */
    private var flashPosition = AdapterView.INVALID_POSITION
    private var scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        findViewById<View>(R.id.root).applySystemBarInsetsAsPadding()
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        history = History(this)
        list = findViewById(R.id.list)
        // 月历作为列表的表头，跟着列表一起滚动；没有记录时和列表一起隐藏，只显示空状态
        val header = layoutInflater.inflate(R.layout.view_history_calendar, list, false) as MonthSwipeLayout
        header.accessibilityDelegate = View.AccessibilityDelegate() // 列表默认的代理会把不可点的表头标成“已停用”
        list.addHeaderView(header, null, false)
        calendar = TrainingCalendar(header, ::showDay)
        adapter = HistoryAdapter(this, onOpen = ::showRecord, onLongPress = ::confirmDelete)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                scrollState = state
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) flash()
            }

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
        })
    }

    override fun onStart() {
        super.onStart()
        reload() // 每次回到前台都重新读取，“今天 / 昨天”随日期更新
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun reload() {
        val records = history.load()
        calendar.submit(records)
        adapter.submit(records)
    }

    /** 月历上点了练过的一天：列表滚到那天的第一条记录，滚完闪一下。 */
    private fun showDay(date: LocalDate) {
        val position = adapter.firstPositionOf(date)
        if (position < 0) return
        flashPosition = list.headerViewsCount + position
        list.smoothScrollToPositionFromTop(flashPosition, (SCROLL_OFFSET_DP * resources.displayMetrics.density).toInt())
        // 列表滚不动（记录少，或已经在那儿）时没有滚动回调，稍后直接闪
        list.postDelayed({ if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) flash() }, FLASH_FALLBACK_MS)
    }

    private fun flash() {
        val position = flashPosition
        if (position == AdapterView.INVALID_POSITION) return
        flashPosition = AdapterView.INVALID_POSITION
        val card = list.getChildAt(position - list.firstVisiblePosition) ?: return
        card.drawableHotspotChanged(card.width / 2f, card.height / 2f)
        card.isPressed = true
        card.postDelayed({ card.isPressed = false }, FLASH_MS)
    }

    private fun showRecord(record: WorkoutRecord) {
        if (dialog?.isShowing == true) return
        dialog = RecordDialog.show(
            this, record,
            onSave = { edited ->
                history.replace(record, edited)
                reload()
            },
            onDelete = { confirmDelete(record) },
        )
    }

    private fun confirmDelete(record: WorkoutRecord) {
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.history_delete_title)
            .setMessage(RecordFormat(this).description(record))
            .setPositiveButton(R.string.history_delete) { _, _ ->
                history.remove(record)
                reload()
            }
            .setNegativeButton(R.string.history_delete_cancel, null)
            .show()
    }

    private companion object {
        const val SCROLL_OFFSET_DP = 8f
        const val FLASH_FALLBACK_MS = 150L
        const val FLASH_MS = 400L
    }
}

/**
 * 训练记录列表：每月一个标题（年月 + 当月次数），下面是当月每次训练的卡片。
 * 列表项本身不可点（isEnabled = false），卡片自己处理点击（详情）和长按（删除）。
 */
private class HistoryAdapter(
    private val context: Context,
    private val onOpen: (WorkoutRecord) -> Unit,
    private val onLongPress: (WorkoutRecord) -> Unit,
) : BaseAdapter() {

    private sealed interface Row

    private class MonthRow(val title: String, val count: String, val description: String) : Row

    private class RecordRow(
        val record: WorkoutRecord,
        val date: LocalDate,
        val day: String,
        val weekday: String,
        val summary: String,
        val detail: String,
        val description: String,
    ) : Row

    private val res = context.resources
    private val inflater = LayoutInflater.from(context)
    private var rows: List<Row> = emptyList()

    /**
     * 列表项都换成自己的无障碍代理：列表默认的代理会把不可点的列表项（月份标题）标成“已停用”。
     * 卡片上的点击读作“查看详情”，长按读作“删除”。
     */
    private val monthAccessibility = View.AccessibilityDelegate()
    private val recordAccessibility = ActionLabels(
        AccessibilityNodeInfo.ACTION_CLICK to res.getString(R.string.history_open),
        AccessibilityNodeInfo.ACTION_LONG_CLICK to res.getString(R.string.history_delete),
    )

    /** @param records 最新的在前（[History.load]）。 */
    fun submit(records: List<WorkoutRecord>) {
        val format = RecordFormat(context)
        val result = ArrayList<Row>()
        records.groupBy { YearMonth.from(format.date(it)) }
            .forEach { (month, monthRecords) ->
                val title = res.getString(R.string.history_month, month.year, month.monthValue)
                result.add(
                    MonthRow(
                        title = title,
                        count = res.getString(R.string.history_month_count, monthRecords.size),
                        description = res.getString(R.string.cd_history_month, title, monthRecords.size),
                    ),
                )
                monthRecords.mapTo(result) { recordRow(it, format) }
            }
        rows = result
        notifyDataSetChanged()
    }

    /** 某天第一条（最新的）记录在列表里的位置；那天没有记录时返回 -1。 */
    fun firstPositionOf(date: LocalDate): Int = rows.indexOfFirst { it is RecordRow && it.date == date }

    private fun recordRow(r: WorkoutRecord, format: RecordFormat): RecordRow {
        val date = format.date(r)
        return RecordRow(
            record = r,
            date = date,
            day = String.format(Locale.ROOT, "%d", date.dayOfMonth),
            weekday = format.weekday(date),
            summary = format.summary(r),
            detail = res.getString(R.string.history_detail, format.time(r.startedAt), TimeFormat.workoutDuration(res, r.durationMs)),
            description = format.description(r),
        )
    }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int = if (rows[position] is MonthRow) TYPE_MONTH else TYPE_RECORD

    override fun areAllItemsEnabled(): Boolean = false

    override fun isEnabled(position: Int): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val row = rows[position]) {
            is MonthRow -> bindMonth(convertView ?: inflater.inflate(R.layout.item_history_month, parent, false), row)
            is RecordRow -> bindRecord(convertView ?: inflater.inflate(R.layout.item_history_record, parent, false), row)
        }

    private fun bindMonth(view: View, row: MonthRow): View {
        view.findViewById<TextView>(R.id.month).text = row.title
        view.findViewById<TextView>(R.id.count).text = row.count
        view.contentDescription = row.description
        view.accessibilityDelegate = monthAccessibility
        return view
    }

    private fun bindRecord(view: View, row: RecordRow): View {
        view.findViewById<TextView>(R.id.day).text = row.day
        view.findViewById<TextView>(R.id.weekday).text = row.weekday
        view.findViewById<TextView>(R.id.summary).text = row.summary
        view.findViewById<TextView>(R.id.detail).text = row.detail
        view.findViewById<TextView>(R.id.note).apply {
            text = row.record.note
            visibility = if (row.record.note.isEmpty()) View.GONE else View.VISIBLE
        }
        view.contentDescription = row.description
        view.accessibilityDelegate = recordAccessibility
        view.setOnClickListener { onOpen(row.record) }
        view.setOnLongClickListener {
            onLongPress(row.record)
            true
        }
        return view
    }

    private companion object {
        const val TYPE_MONTH = 0
        const val TYPE_RECORD = 1
    }
}
