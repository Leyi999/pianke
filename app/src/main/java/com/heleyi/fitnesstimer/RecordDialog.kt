package com.heleyi.fitnesstimer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView

/**
 * 一次训练的详情（点记录卡片打开）：开始 – 结束时间和精确用时、动作数 · 组数、各动作组数；
 * 可以改练了哪些部位和备注（保存时才写入），或者删除这条记录。
 */
object RecordDialog {

    @SuppressLint("InflateParams") // 对话框的内容没有父布局
    fun show(
        activity: Activity,
        record: WorkoutRecord,
        onSave: (WorkoutRecord) -> Unit,
        onDelete: () -> Unit,
    ): AlertDialog {
        val res = activity.resources
        val format = RecordFormat(activity)
        val builder = AlertDialog.Builder(activity)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_record, null)

        val date = format.date(record)
        val title = if (date.year == format.today.year) {
            res.getString(R.string.record_title, date.monthValue, date.dayOfMonth, format.dayOfWeek(date))
        } else {
            res.getString(R.string.record_title_year, date.year, date.monthValue, date.dayOfMonth, format.dayOfWeek(date))
        }
        view.findViewById<TextView>(R.id.recordTime).text = res.getString(
            R.string.record_time, format.time(record.startedAt), format.time(record.endedAt), TimeFormat.hhmmss(record.durationMs),
        )
        view.findViewById<TextView>(R.id.recordSummary).text =
            res.getString(R.string.history_summary, record.exercises, record.sets)
        val perExercise = format.setsPerExercise(record)
        view.findViewById<TextView>(R.id.recordPerExercise).apply {
            visibility = if (perExercise != null) View.VISIBLE else View.GONE
            if (perExercise != null) text = res.getString(R.string.record_per_exercise, perExercise)
        }
        view.findViewById<View>(R.id.recordAuto).visibility = if (record.autoEnded) View.VISIBLE else View.GONE
        val picker = PartPicker(view.findViewById(R.id.parts), record.parts)
        val note = view.findViewById<EditText>(R.id.note)
        note.setText(record.note)

        val dialog = builder.setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.record_save) { _, _ ->
                val edited = record.copy(parts = picker.selected, note = note.text.toString().trim())
                if (edited != record) onSave(edited)
            }
            .setNegativeButton(R.string.history_delete_cancel, null)
            .setNeutralButton(R.string.history_delete) { d, _ ->
                d.dismiss() // 按钮回调时对话框还没关；先关掉，接着弹出删除确认
                onDelete()
            }
            .create()
        // 只是来看看的话不弹键盘，点备注框才输入（内容在 ScrollView 里，键盘弹出时系统会缩小对话框）
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        return dialog
    }
}
