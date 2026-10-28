package com.heleyi.fitnesstimer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 一次训练的记录。 */
data class WorkoutRecord(
    /** 开始时间：系统时间（毫秒）。 */
    val startedAt: Long,
    /** 用时：从开始到点“结束”，和结束确认框里的“用时”一致；自动结束的算到最后一次操作。 */
    val durationMs: Long,
    /** 练了几个动作。 */
    val exercises: Int,
    /** 共完成几组。 */
    val sets: Int,
    /** 每个动作的组数，例如 [4, 4, 3]；旧记录、从旧版本恢复的训练没有这项，为空。 */
    val setsPerExercise: List<Int> = emptyList(),
    /** 练了哪些部位（结束时或在详情里选，可不选）。 */
    val parts: Set<BodyPart> = emptySet(),
    /** 备注。 */
    val note: String = "",
    /** 没点“结束”、自动结束的训练（闲置太久，或应用被回收后作废）。 */
    val autoEnded: Boolean = false,
) {
    val endedAt: Long get() = startedAt + durationMs

    companion object {
        /** 一组都没完成（开始后直接结束）的训练不记，返回 null。 */
        fun of(
            s: WorkoutState,
            startedAt: Long,
            durationMs: Long,
            parts: Set<BodyPart> = emptySet(),
            autoEnded: Boolean = false,
        ): WorkoutRecord? {
            if (s.completedSets <= 0) return null
            return WorkoutRecord(
                startedAt, durationMs, s.exercisesDone, s.completedSets,
                setsPerExercise = s.setsPerExercise.orEmpty(), parts = parts, autoEnded = autoEnded,
            )
        }
    }
}

/** 训练部位；记录里存 [key]，改显示文字不影响旧记录。显示顺序就是这里的顺序。 */
enum class BodyPart(val key: String, val label: Int) {
    CHEST("chest", R.string.part_chest),
    BACK("back", R.string.part_back),
    SHOULDERS("shoulders", R.string.part_shoulders),
    LEGS("legs", R.string.part_legs),
    ARMS("arms", R.string.part_arms),
    CORE("core", R.string.part_core),
    FULL_BODY("full_body", R.string.part_full_body);

    companion object {
        fun of(key: String): BodyPart? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 训练记录：每次结束训练时自动记下一条，只存在本机。
 * 单独一个 SharedPreferences 文件，整个列表存成一段 JSON；和设置一样不参与云备份、换机迁移（见 data_extraction_rules）。
 */
class History(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 全部记录，最新的在前。 */
    fun load(): List<WorkoutRecord> {
        val json = sp.getString(KEY_RECORDS, null) ?: return emptyList()
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            Log.w(TAG, "unreadable history", e)
            return emptyList()
        }
        val records = ArrayList<WorkoutRecord>(array.length())
        for (i in 0 until array.length()) {
            parse(array.optJSONObject(i) ?: continue)?.let { records.add(it) }
        }
        records.sortByDescending { it.startedAt }
        return records
    }

    fun add(record: WorkoutRecord) = save(load() + record)

    fun remove(record: WorkoutRecord) {
        val records = load().toMutableList()
        if (records.remove(record)) save(records)
    }

    /** 用 [new] 替换 [old]（改部位、备注）。 */
    fun replace(old: WorkoutRecord, new: WorkoutRecord) {
        val records = load().toMutableList()
        val i = records.indexOf(old)
        if (i < 0) return
        records[i] = new
        save(records)
    }

    private fun parse(o: JSONObject): WorkoutRecord? {
        val exercises = o.optInt(KEY_EXERCISES)
        val sets = o.optInt(KEY_SETS)
        val perExercise = o.optJSONArray(KEY_PER_EXERCISE)?.let { a -> List(a.length()) { a.optInt(it) } }.orEmpty()
        val parts = o.optJSONArray(KEY_PARTS)?.let { a -> (0 until a.length()).mapNotNull { BodyPart.of(a.optString(it)) } }
        val r = WorkoutRecord(
            startedAt = o.optLong(KEY_START),
            durationMs = o.optLong(KEY_DURATION, -1L),
            exercises = exercises,
            sets = sets,
            // 明细要和总数对得上，否则不显示
            setsPerExercise = perExercise.takeIf { it.size == exercises && it.sum() == sets && it.all { n -> n > 0 } }.orEmpty(),
            parts = parts.orEmpty().toSet(),
            note = o.optString(KEY_NOTE, ""),
            autoEnded = o.optBoolean(KEY_AUTO, false),
        )
        return r.takeIf { it.startedAt > 0L && it.durationMs >= 0L && it.exercises > 0 && it.sets > 0 }
    }

    private fun save(records: List<WorkoutRecord>) {
        val array = JSONArray()
        for (r in records.sortedByDescending { it.startedAt }) {
            val o = JSONObject()
                .put(KEY_START, r.startedAt)
                .put(KEY_DURATION, r.durationMs)
                .put(KEY_EXERCISES, r.exercises)
                .put(KEY_SETS, r.sets)
            // 可选的几项只在有内容时写，旧版本读到也不受影响
            if (r.setsPerExercise.isNotEmpty()) o.put(KEY_PER_EXERCISE, JSONArray(r.setsPerExercise))
            if (r.parts.isNotEmpty()) o.put(KEY_PARTS, JSONArray(r.parts.sortedBy { it.ordinal }.map { it.key }))
            if (r.note.isNotEmpty()) o.put(KEY_NOTE, r.note)
            if (r.autoEnded) o.put(KEY_AUTO, true)
            array.put(o)
        }
        sp.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private companion object {
        const val TAG = "History"
        const val FILE = "history"
        const val KEY_RECORDS = "records"
        const val KEY_START = "start"
        const val KEY_DURATION = "duration"
        const val KEY_EXERCISES = "exercises"
        const val KEY_SETS = "sets"
        const val KEY_PER_EXERCISE = "perExercise"
        const val KEY_PARTS = "parts"
        const val KEY_NOTE = "note"
        const val KEY_AUTO = "auto"
    }
}
