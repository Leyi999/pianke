package com.heleyi.fitnesstimer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 进程内唯一的训练会话：持有状态机，按显示秒数变化的节奏刷新，倒计时归零时发出提醒，
 * 并通知界面与前台服务；结束时记入训练记录（[History]）。所有方法都在主线程调用。
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

    /** 进程被系统回收后重新打开应用时，恢复未结束的训练；已经作废的（设备重启过、太久没有操作）照样记入训练记录。 */
    fun restoreIfNeeded(context: Context) {
        if (restoreChecked || workout != null) return
        restoreChecked = true
        val app = context.applicationContext
        val prefs = Prefs(app)
        val saved = prefs.loadWorkout(now()) { History(app).add(it) } ?: return
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

    /**
     * 结束训练，记入训练记录：开始时间换算成系统时间，用时和结束确认框里的一致。
     * @param parts 结束时选的训练部位
     */
    fun stop(parts: Set<BodyPart> = emptySet()) = end(now(), parts, autoEnded = false)

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** @param endAt 结束时间（elapsedRealtime）；自动结束时是最后一次操作的时间。 */
    private fun end(endAt: Long, parts: Set<BodyPart>, autoEnded: Boolean) {
        val w = workout
        handler.removeCallbacks(tick)
        workout = null
        appContext?.let { app ->
            if (w != null) {
                val s = w.state
                val startedAt = System.currentTimeMillis() - (now() - s.startedAt)
                val duration = (endAt - s.startedAt).coerceAtLeast(0L)
                WorkoutRecord.of(s, startedAt, duration, parts, autoEnded)?.let { History(app).add(it) }
            }
            Prefs(app).clearWorkout()
        }
        dispatch()
    }

    private inline fun act(block: (Workout) -> Boolean) {
        val w = workout ?: return
        val alert = block(w)
        persist()
        afterChange(alert)
    }

    /**
     * 每次显示的秒数变化时刷新。太久没有操作就当作忘了点结束：自动结束并记下，用时算到最后一次操作，
     * 超级岛和通知随之收起。熄屏时 CPU 休眠、这里不走，下次唤醒后马上补上。
     */
    private fun onTick() {
        val w = workout ?: return
        val now = now()
        if (w.isAbandoned(now)) return end(w.state.phaseStartedAt, emptySet(), autoEnded = true)
        afterChange(alert = w.update(now))
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
