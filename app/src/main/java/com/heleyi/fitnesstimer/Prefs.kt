package com.heleyi.fitnesstimer

import android.content.Context
import android.content.SharedPreferences
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
            .putLong(KEY_STARTED, s.startedAt)
            .putInt(KEY_EXERCISE, s.exercise)
            .putInt(KEY_SET, s.set)
            .putBoolean(KEY_RESTING, s.resting)
            .putLong(KEY_PHASE_AT, s.phaseStartedAt)
            .putInt(KEY_COMPLETED, s.completedSets)
            .putBoolean(KEY_NEXT_NEW, s.nextNewExercise)
            .apply()
    }

    fun clearWorkout() {
        sp.edit().putBoolean(KEY_ACTIVE, false).apply()
    }


    /** 读取未结束的训练；设备重启过或已闲置超过 6 小时则作废。 */
    fun loadWorkout(now: Long): WorkoutState? {
        if (!sp.getBoolean(KEY_ACTIVE, false)) return null
        val s = WorkoutState(
            startedAt = sp.getLong(KEY_STARTED, 0L),
            exercise = sp.getInt(KEY_EXERCISE, 1).coerceAtLeast(1),
            set = sp.getInt(KEY_SET, 1).coerceAtLeast(1),
            resting = sp.getBoolean(KEY_RESTING, false),
            phaseStartedAt = sp.getLong(KEY_PHASE_AT, 0L),
            completedSets = sp.getInt(KEY_COMPLETED, 0).coerceAtLeast(0),
            nextNewExercise = sp.getBoolean(KEY_NEXT_NEW, false),
        )
        val idle = now - s.phaseStartedAt
        val valid = sp.getInt(KEY_BOOT, -1) == bootCount() &&
            s.startedAt <= s.phaseStartedAt && idle in 0L..MAX_IDLE_MS
        if (!valid) {
            clearWorkout()
            return null
        }
        return s
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
        const val KEY_STARTED = "w_started"
        const val KEY_EXERCISE = "w_exercise"
        const val KEY_SET = "w_set"
        const val KEY_RESTING = "w_resting"
        const val KEY_PHASE_AT = "w_phase_at"
        const val KEY_COMPLETED = "w_completed"
        const val KEY_NEXT_NEW = "w_next_new"
        const val MAX_IDLE_MS = 6 * 60 * 60 * 1000L
    }
}
