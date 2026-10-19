package com.heleyi.fitnesstimer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 进程内唯一的训练会话：持有状态机，按显示秒数变化的节奏刷新，倒计时归零时发出提醒，
 * 并通知界面与前台服务。所有方法都在主线程调用。
 */
@SuppressLint("StaticFieldLeak") // 只持有 Application Context，不会泄漏 Activity
object WorkoutSession {

    fun interface Listener {
        fun onWorkoutChanged()
    }

    var workout: Workout? = null
        private set

    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val tick = Runnable { onTick() }
    private var restoreChecked = false
    private const val TICK_SLACK_MS = 5L

    fun now(): Long = SystemClock.elapsedRealtime()

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        restoreChecked = true
        val now = now()
        workout = Workout(WorkoutState(startedAt = now), Prefs(app).restSeconds * 1000L, now)
        persist()
        TimerService.start(app)
        afterChange(alert = false)
    }

    /** 进程被系统回收后重新打开应用时，恢复未结束的训练。 */
    fun restoreIfNeeded(context: Context) {
        if (restoreChecked || workout != null) return
        restoreChecked = true
        val app = context.applicationContext
        val prefs = Prefs(app)
        val saved = prefs.loadWorkout(now()) ?: return
        appContext = app
        workout = Workout(saved, prefs.restSeconds * 1000L, now())
        TimerService.start(app)
        afterChange(alert = false)
    }


    fun finishSet() = act { it.finishSet(now()); false }

    /** 完成本组并在休息后换到下个动作（超级岛上训练中的“下一个动作”按钮）。 */
    fun finishExercise() = act { it.finishSet(now(), nextExercise = true); false }

    fun nextSet() = act { it.startNextSet(now()); false }

    fun nextExercise() = act { it.toggleNextExercise(now()); false }

    fun undo() = act { it.undo(now()); false }

    /** 组间休息时长改变，立即作用于当前倒计时。 */
    fun setRestSeconds(seconds: Int) = act { it.setRest(seconds * 1000L, now()) }

    /** 结束训练。 */
    fun stop() {
        handler.removeCallbacks(tick)
        workout = null
        appContext?.let { Prefs(it).clearWorkout() }
        dispatch()
    }

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private inline fun act(block: (Workout) -> Boolean) {
        val w = workout ?: return
        val alert = block(w)
        persist()
        afterChange(alert)
    }

    private fun onTick() {
        val w = workout ?: return
        afterChange(alert = w.update(now()))
    }

    private fun afterChange(alert: Boolean) {
        val w = workout ?: return
        if (alert) appContext?.let { Alerts.fire(it) }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, w.msUntilNextTick(now()) + TICK_SLACK_MS)
        dispatch()
    }

    private fun persist() {
        val w = workout ?: return
        appContext?.let { Prefs(it).saveWorkout(w.state) }
    }

    private fun dispatch() {
        for (listener in listeners.toTypedArray()) listener.onWorkoutChanged()
    }
}
