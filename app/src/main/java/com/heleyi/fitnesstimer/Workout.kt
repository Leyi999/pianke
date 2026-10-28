package com.heleyi.fitnesstimer

/**
 * 训练进度快照：第几个动作、第几组，处于训练还是组间休息。
 * 时间均为 SystemClock.elapsedRealtime() 毫秒，不受修改系统时间影响。
 */
data class WorkoutState(
    val startedAt: Long,
    val exercise: Int = 1,
    /** 训练中：正在做的组；休息中：刚完成的组。 */
    val set: Int = 1,
    val resting: Boolean = false,
    val phaseStartedAt: Long = startedAt,
    val completedSets: Int = 0,
    /** 仅休息中有效：下一组是否换到下一个动作（休息继续，不打断倒计时）。 */
    val nextNewExercise: Boolean = false,
    /** 之前每个动作完成的组数（不含当前动作）；从没有这项数据的旧版本恢复时为 null，不记明细。 */
    val earlierSets: List<Int>? = emptyList(),
) {
    /** 已练过的动作数（当前动作至少完成一组才计入）。 */
    val exercisesDone: Int
        get() = if (resting || set > 1) exercise else exercise - 1

    /** 每个动作完成的组数，例如 [4, 4, 3]（当前动作至少完成一组才计入）；不知道时为 null。 */
    val setsPerExercise: List<Int>?
        get() {
            val current = if (resting) set else set - 1
            return if (current > 0) earlierSets?.plus(current) else earlierSets
        }
}

/**
 * 训练状态机（纯逻辑）：训练中 —完成本组→ 组间休息（倒计时，归零后继续正计时）
 * —下一组 / 下一个动作→ 训练中。
 */
class Workout(initial: WorkoutState, restMs: Long, now: Long) {

    var state: WorkoutState = initial
        private set

    /** 组间休息时长；休息中调整会立即作用于当前倒计时。 */
    var restMs: Long = restMs
        private set

    private val history = ArrayDeque<WorkoutState>()

    /** 倒计时尚未归零；归零那一刻需要提醒一次。恢复时若已超时则不再补提醒。 */
    private var alertArmed = initial.resting && restMs - (now - initial.phaseStartedAt) > 0L

    val canUndo: Boolean get() = history.isNotEmpty()

    fun phaseElapsedMs(now: Long): Long = (now - state.phaseStartedAt).coerceAtLeast(0L)

    /** 休息剩余毫秒；≤ 0 表示倒计时已结束，其绝对值就是多休息的时长。 */
    fun restRemainingMs(now: Long): Long = restMs - phaseElapsedMs(now)

    fun isOvertime(now: Long): Boolean = state.resting && restRemainingMs(now) <= 0L

    fun totalElapsedMs(now: Long): Long = (now - state.startedAt).coerceAtLeast(0L)

    /** 超过 [ABANDON_AFTER_MS] 没有操作（休息超时，或一组开始后一直没点完成），当作忘了点结束。 */
    fun isAbandoned(now: Long): Boolean = phaseElapsedMs(now) >= ABANDON_AFTER_MS

    /** 完成本组。nextExercise = true：这是本动作的最后一组，休息后换到下个动作的第 1 组（一步即可撤销）。 */
    fun finishSet(now: Long, nextExercise: Boolean = false) {
        if (state.resting) return
        push()
        state = state.copy(
            resting = true, phaseStartedAt = now, completedSets = state.completedSets + 1,
            nextNewExercise = nextExercise,
        )
        alertArmed = restMs > 0L
    }

    /** 休息中显示的“下一组”：默认本动作的下一组，选了“下一个动作”则为下个动作的第 1 组。 */
    val upcomingExercise: Int
        get() = if (state.resting && state.nextNewExercise) state.exercise + 1 else state.exercise

    val upcomingSet: Int
        get() = when {
            !state.resting -> state.set
            state.nextNewExercise -> 1
            else -> state.set + 1
        }

    /** 结束休息，开始“下一组”；换到下个动作时记下刚练完的动作做了几组。 */
    fun startNextSet(now: Long) {
        if (!state.resting) return
        push()
        state = state.copy(
            resting = false, exercise = upcomingExercise, set = upcomingSet,
            phaseStartedAt = now, nextNewExercise = false,
            earlierSets = if (state.nextNewExercise) state.earlierSets?.plus(state.set) else state.earlierSets,
        )
        alertArmed = false
    }

    /**
     * 休息中点“下一个动作”：倒计时还没结束时继续休息，只把下一组切换为下个动作的第 1 组（再点一次切回）；
     * 休息已经结束（超时）时直接开始下个动作的第 1 组。
     */
    fun toggleNextExercise(now: Long) {
        if (!state.resting) return
        if (!state.nextNewExercise && isOvertime(now)) {
            push()
            state = state.copy(
                resting = false, exercise = state.exercise + 1, set = 1, phaseStartedAt = now,
                earlierSets = state.earlierSets?.plus(state.set),
            )
            alertArmed = false
            return
        }
        push()
        state = state.copy(nextNewExercise = !state.nextNewExercise)
    }

    /** 撤销上一步：回到当时的阶段，计时按原起点继续，等同于没点过。 */
    fun undo(now: Long) {
        val previous = history.removeLastOrNull() ?: return
        state = previous
        alertArmed = state.resting && restRemainingMs(now) > 0L
    }

    /** 调整组间休息时长；返回 true 表示调整后倒计时随即归零（需要提醒）。 */
    fun setRest(ms: Long, now: Long): Boolean {
        restMs = ms
        return update(now)
    }

    /** 返回 true 表示倒计时刚刚归零（提醒事件），每次归零只返回一次。 */
    fun update(now: Long): Boolean {
        if (!state.resting) return false
        if (restRemainingMs(now) > 0L) {
            alertArmed = true // 休息被调长后重新计时，下次归零仍会提醒
            return false
        }
        if (!alertArmed) return false
        alertArmed = false
        return true
    }

    /** 距离显示的秒数下一次变化（或归零）的毫秒数。 */
    fun msUntilNextTick(now: Long): Long {
        if (!state.resting) return 1000L - phaseElapsedMs(now) % 1000L
        val remaining = restRemainingMs(now)
        return if (remaining > 0L) (remaining - 1L) % 1000L + 1L else 1000L - (-remaining) % 1000L
    }

    private fun push() {
        history.addLast(state)
        if (history.size > MAX_UNDO) history.removeFirst()
    }

    companion object {
        private const val MAX_UNDO = 100

        /** 多久没有操作就自动结束训练（应用在后台被回收后，超过这个时长也不再恢复）。 */
        const val ABANDON_AFTER_MS = 30 * 60 * 1000L
    }
}
