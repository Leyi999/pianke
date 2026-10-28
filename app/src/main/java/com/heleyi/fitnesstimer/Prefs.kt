package com.heleyi.fitnesstimer

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/** 本地持久化（仅存于本机）：响铃/震动开关、组间休息时长、进行中的训练进度。 */
class Prefs(context: Context) {

    private val app = context.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 响铃开关，首次安装默认开启；与震动互相独立。 */
    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit().putBoolean(KEY_SOUND, value).apply()

    /** 震动开关，首次安装默认开启；与响铃互相独立。 */
    var vibrateEnabled: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(value) = sp.edit().putBoolean(KEY_VIBRATE, value).apply()

    /** 组间休息秒数。 */
    var restSeconds: Int
        get() = sp.getInt(KEY_REST, RestAdjuster.DEFAULT).coerceIn(RestAdjuster.MIN, RestAdjuster.MAX)
        set(value) = sp.edit().putInt(KEY_REST, value).apply()

    /** 保存进行中的训练：进程被系统回收后重新打开应用可以接着练。 */
    fun saveWorkout(s: WorkoutState) {
        sp.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_BOOT, bootCount())
            .putLong(KEY_WALL_OFFSET, System.currentTimeMillis() - SystemClock.elapsedRealtime())
            .putLong(KEY_STARTED, s.startedAt)
            .putInt(KEY_EXERCISE, s.exercise)
            .putInt(KEY_SET, s.set)
            .putBoolean(KEY_RESTING, s.resting)
            .putLong(KEY_PHASE_AT, s.phaseStartedAt)
            .putInt(KEY_COMPLETED, s.completedSets)
            .putBoolean(KEY_NEXT_NEW, s.nextNewExercise)
            .putString(KEY_EARLIER_SETS, s.earlierSets?.joinToString(",")) // null：不知道明细，删掉这一项
            .apply()
    }

    fun clearWorkout() {
        sp.edit().putBoolean(KEY_ACTIVE, false).apply()
    }


    /**
     * 读取未结束的训练；设备重启过或已超过 [Workout.ABANDON_AFTER_MS] 没有操作则作废。
     * 作废的训练（应用被系统回收后没再点“结束”）完成过组的，交给 [onExpired] 记入训练记录，结束时间按最后一次操作算。
     */
    fun loadWorkout(now: Long, onExpired: (WorkoutRecord) -> Unit): WorkoutState? {
        if (!sp.getBoolean(KEY_ACTIVE, false)) return null
        val exercise = sp.getInt(KEY_EXERCISE, 1).coerceAtLeast(1)
        val s = WorkoutState(
            startedAt = sp.getLong(KEY_STARTED, 0L),
            exercise = exercise,
            set = sp.getInt(KEY_SET, 1).coerceAtLeast(1),
            resting = sp.getBoolean(KEY_RESTING, false),
            phaseStartedAt = sp.getLong(KEY_PHASE_AT, 0L),
            completedSets = sp.getInt(KEY_COMPLETED, 0).coerceAtLeast(0),
            nextNewExercise = sp.getBoolean(KEY_NEXT_NEW, false),
            earlierSets = earlierSets(exercise),
        )
        val idle = now - s.phaseStartedAt
        val valid = sp.getInt(KEY_BOOT, -1) == bootCount() &&
            s.startedAt <= s.phaseStartedAt && idle in 0L until Workout.ABANDON_AFTER_MS
        if (!valid) {
            expiredRecord(s)?.let(onExpired)
            clearWorkout()
            return null
        }
        return s
    }

    /** 之前各动作的组数；旧版本没存这项（且已经换过动作）或数据对不上时为 null。 */
    private fun earlierSets(exercise: Int): List<Int>? {
        val raw = sp.getString(KEY_EARLIER_SETS, null) ?: return if (exercise == 1) emptyList() else null
        val sets = if (raw.isEmpty()) emptyList() else raw.split(',').map { it.toIntOrNull() ?: return null }
        return sets.takeIf { it.size == exercise - 1 && it.all { n -> n > 0 } }
    }

    /** 重启后 elapsedRealtime 从零算起，开始时间用保存时记下的“系统时间 − elapsedRealtime”换算。 */
    private fun expiredRecord(s: WorkoutState): WorkoutRecord? {
        if (!sp.contains(KEY_WALL_OFFSET) || s.startedAt > s.phaseStartedAt) return null
        val wallOffset = sp.getLong(KEY_WALL_OFFSET, 0L)
        return WorkoutRecord.of(s, s.startedAt + wallOffset, s.phaseStartedAt - s.startedAt, autoEnded = true)
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private companion object {
        const val FILE = "settings"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_VIBRATE = "vibrate_enabled"
        const val KEY_REST = "rest_seconds"
        const val KEY_ACTIVE = "w_active"
        const val KEY_BOOT = "w_boot"
        const val KEY_WALL_OFFSET = "w_wall_offset"
        const val KEY_STARTED = "w_started"
        const val KEY_EXERCISE = "w_exercise"
        const val KEY_SET = "w_set"
        const val KEY_RESTING = "w_resting"
        const val KEY_PHASE_AT = "w_phase_at"
        const val KEY_COMPLETED = "w_completed"
        const val KEY_NEXT_NEW = "w_next_new"
        const val KEY_EARLIER_SETS = "w_earlier_sets"
    }
}
