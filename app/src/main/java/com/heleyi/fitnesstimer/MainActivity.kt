package com.heleyi.fitnesstimer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Locale

/**
 * 唯一界面。未开始：组间休息调节 + 开始训练。
 * 训练中：显示第几个动作、第几组；完成一组后开始休息倒计时，归零时提醒，之后继续计时显示多休息了多久。
 * 分屏等矮窗口里自动收紧布局，计时圆环始终可见，见 [updateLayoutMode]。
 */
class MainActivity : Activity(), WorkoutSession.Listener {

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var idlePanel: View
    private lateinit var idleLabel: View
    private lateinit var idleValue: TextView
    private lateinit var idleButtons: View
    private lateinit var idleHint: View
    private lateinit var trainingPanel: View
    private lateinit var stage: LinearLayout
    private lateinit var ringFrame: View
    private lateinit var counters: View
    private lateinit var restRow: View
    private lateinit var toggleSound: ToggleButton
    private lateinit var toggleVibrate: ToggleButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnEnd: Button
    private lateinit var ring: RingView
    private lateinit var phaseLabel: TextView
    private lateinit var timerText: TextView
    private lateinit var timerCaption: TextView
    private lateinit var exerciseLabel: TextView
    private lateinit var exerciseText: TextView
    private lateinit var setLabel: TextView
    private lateinit var setText: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button
    private lateinit var idleRest: RestAdjuster
    private lateinit var trainingRest: RestAdjuster

    private var hasVibrator = true
    private var endDialog: AlertDialog? = null
    private var lastActionAt = Long.MIN_VALUE / 2
    private val backCallback = OnBackInvokedCallback { confirmEnd() }
    private var backIntercepting = false

    /** 根布局除去系统栏后的可用高度（像素），变化时重新选择布局，见 [updateLayoutMode]。 */
    private var lastAvailHeight = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        volumeControlStream = AudioManager.STREAM_MUSIC // 音量键调节提示音（媒体音量）
        root = findViewById(R.id.root)
        root.applySystemBarInsetsAsPadding()
        prefs = Prefs(this)
        hasVibrator = Alerts.hasVibrator(this)

        topBar = findViewById(R.id.topBar)
        idlePanel = findViewById(R.id.idlePanel)
        idleLabel = findViewById(R.id.idleLabel)
        idleValue = findViewById(R.id.idleValue)
        idleButtons = findViewById(R.id.idleButtons)
        idleHint = findViewById(R.id.idleHint)
        trainingPanel = findViewById(R.id.trainingPanel)
        stage = findViewById(R.id.stage)
        ringFrame = findViewById(R.id.ringFrame)
        counters = findViewById(R.id.counters)
        restRow = findViewById(R.id.restRow)
        toggleSound = findViewById(R.id.toggleSound)
        toggleVibrate = findViewById(R.id.toggleVibrate)
        btnUndo = findViewById(R.id.btnUndo)
        btnEnd = findViewById(R.id.btnEnd)
        ring = findViewById(R.id.ring)
        phaseLabel = findViewById(R.id.phaseLabel)
        timerText = findViewById(R.id.timerText)
        timerCaption = findViewById(R.id.timerCaption)
        exerciseLabel = findViewById(R.id.exerciseLabel)
        exerciseText = findViewById(R.id.exerciseText)
        setLabel = findViewById(R.id.setLabel)
        setText = findViewById(R.id.setText)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)
        idleRest = RestAdjuster(
            findViewById(R.id.idleMinus), idleValue, findViewById(R.id.idlePlus), ::onRestChanged,
        )
        trainingRest = RestAdjuster(
            findViewById(R.id.restMinus), findViewById(R.id.restValue), findViewById(R.id.restPlus), ::onRestChanged,
        )
        // 计时数字随圆环直径等比缩放，保证 “+00:00” 放得下
        ring.onDiameterChanged = { d -> timerText.setTextSize(TypedValue.COMPLEX_UNIT_PX, d * TIMER_TEXT_RATIO) }
        // 分屏、调整分屏比例时窗口高度改变：在布局完成后按可用高度重新选择布局
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val avail = v.height - v.paddingTop - v.paddingBottom
            if (avail > 0 && avail != lastAvailHeight) {
                lastAvailHeight = avail
                v.post { updateLayoutMode(avail) }
            }
        }

        setUpToggles()
        btnPrimary.setOnClickListener { onPrimaryClicked() }
        btnSecondary.setOnClickListener { if (debounce()) WorkoutSession.nextExercise() }
        btnUndo.setOnClickListener { if (debounce()) WorkoutSession.undo() }
        btnEnd.setOnClickListener { confirmEnd() }

        WorkoutSession.restoreIfNeeded(this)
        if (savedInstanceState?.getBoolean(STATE_END_DIALOG) == true) confirmEnd()
    }

    override fun onStart() {
        super.onStart()
        WorkoutSession.addListener(this)
        syncToggles()
        showRest(prefs.restSeconds)
        render()
    }

    override fun onStop() {
        WorkoutSession.removeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_END_DIALOG, endDialog?.isShowing == true)
    }

    override fun onDestroy() {
        endDialog?.dismiss()
        endDialog = null
        super.onDestroy()
    }

    override fun onWorkoutChanged() = render()

    private fun setUpToggles() {
        for (t in listOf(toggleSound, toggleVibrate)) {
            t.textOn = "" // 纯图标开关
            t.textOff = ""
            t.isSaveEnabled = false // 状态以持久化设置为准
        }
        toggleSound.setOnCheckedChangeListener { _, checked ->
            if (checked != prefs.soundEnabled) {
                prefs.soundEnabled = checked
                if (checked) Alerts.beep(this) // 打开时试听一次，顺便确认媒体音量
            }
        }
        toggleVibrate.isEnabled = hasVibrator
        if (!hasVibrator) toggleVibrate.contentDescription = getString(R.string.vibrate_unsupported)
        toggleVibrate.setOnCheckedChangeListener { _, checked ->
            if (hasVibrator && checked != prefs.vibrateEnabled) {
                prefs.vibrateEnabled = checked
                if (checked) Alerts.buzz(this)
            }
        }
    }

    private fun syncToggles() {
        toggleSound.isChecked = prefs.soundEnabled
        toggleVibrate.isChecked = hasVibrator && prefs.vibrateEnabled
    }

    private fun onRestChanged(seconds: Int) {
        prefs.restSeconds = seconds
        showRest(seconds)
        WorkoutSession.setRestSeconds(seconds)
    }

    private fun showRest(seconds: Int) {
        idleRest.show(seconds)
        trainingRest.show(seconds)
    }

    private fun render() {
        val w = WorkoutSession.workout ?: return renderIdle()
        val now = WorkoutSession.now()
        val s = w.state
        val overtime = w.isOvertime(now)
        idlePanel.visibility = View.GONE
        trainingPanel.visibility = View.VISIBLE
        btnUndo.visibility = View.VISIBLE
        btnEnd.visibility = View.VISIBLE
        btnUndo.isEnabled = w.canUndo

        // 训练中：荧光绿；休息倒计时：冰蓝；超时：丁香紫
        val color = getColor(
            when {
                !s.resting -> R.color.volt
                overtime -> R.color.lilac
                else -> R.color.ice
            }
        )
        when {
            !s.resting -> ring.showSweep(color, s.phaseStartedAt)
            overtime -> ring.showOvertime(color)
            else -> ring.showCountdown(color, s.phaseStartedAt, w.restMs)
        }
        phaseLabel.setTextColor(color)
        phaseLabel.setTextIfChanged(
            getString(
                when {
                    !s.resting -> R.string.phase_set
                    overtime -> R.string.phase_over
                    else -> R.string.phase_rest
                }
            )
        )
        timerText.setTextColor(if (overtime) color else getColor(R.color.text))
        timerText.text = TimeFormat.phaseTimer(w, now)
        timerCaption.setTextIfChanged(
            getString(
                when {
                    !s.resting -> R.string.caption_set
                    overtime -> R.string.caption_over
                    else -> R.string.caption_rest
                }
            )
        )
        exerciseLabel.setTextIfChanged(
            getString(if (s.resting && s.nextNewExercise) R.string.label_next_exercise else R.string.label_exercise)
        )
        setLabel.setTextIfChanged(getString(if (s.resting) R.string.label_next_set else R.string.label_set))
        exerciseText.setTextIfChanged(twoDigits(w.upcomingExercise))
        setText.setTextIfChanged(twoDigits(w.upcomingSet))
        val counterDesc = if (s.resting) {
            getString(R.string.cd_next, w.upcomingExercise, w.upcomingSet)
        } else {
            getString(R.string.cd_current, s.exercise, s.set)
        }
        exerciseText.contentDescription = counterDesc
        setText.contentDescription = counterDesc
        if (s.resting) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.setTextIfChanged(
                if (s.nextNewExercise) getString(R.string.back_to_exercise_n, s.exercise) else getString(R.string.next_exercise)
            )
            btnPrimary.setTextIfChanged(getString(R.string.start_set_n, w.upcomingSet))
        } else {
            btnSecondary.visibility = View.GONE
            btnPrimary.setTextIfChanged(getString(R.string.finish_set))
        }
        setKeepScreenOn(true)
        setBackIntercept(true)
    }

    private fun renderIdle() {
        endDialog?.dismiss()
        idlePanel.visibility = View.VISIBLE
        trainingPanel.visibility = View.GONE
        btnUndo.visibility = View.GONE
        btnEnd.visibility = View.GONE
        btnSecondary.visibility = View.GONE
        btnPrimary.setTextIfChanged(getString(R.string.start))
        setKeepScreenOn(false)
        setBackIntercept(false)
    }

    private fun twoDigits(n: Int): String = String.format(Locale.ROOT, "%02d", n)

    /**
     * 按可用高度选择布局，保证计时圆环始终可见（分屏时窗口只有全屏的一半甚至三分之一）：
     * - 够高：圆环在上，动作 / 组和组间休息调节在下；
     * - 矮（如上下分屏的一半）：圆环在左、动作 / 组在右，收起组间休息调节（全屏时再调）；
     * - 更矮（如三分之一分屏）：再收起顶栏的响铃 / 震动 / 撤销 / 结束。
     * 未开始界面同样依次收起提示文字、缩小数值，让 − / + 按钮留在屏幕上。
     */
    private fun updateLayoutMode(availPx: Int) {
        val density = resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).toInt()
        val avail = availPx / density
        val compact = avail < COMPACT_BELOW_DP
        val mini = avail < MINI_BELOW_DP
        topBar.visibility = if (mini) View.GONE else View.VISIBLE

        stage.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        ringFrame.layoutParams = if (compact) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, COMPACT_RING_WEIGHT)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        counters.layoutParams = if (compact) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = px(12f)
            }
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(16f)
            }
        }
        restRow.visibility = if (compact) View.GONE else View.VISIBLE

        // 未开始：中部可用高度 = 总高度 − 顶栏 − 底部按钮
        val content = avail - (if (mini) 0f else TOP_BAR_DP) - BOTTOM_BAR_DP
        idleHint.visibility = if (compact) View.GONE else View.VISIBLE
        idleLabel.visibility = if (mini) View.GONE else View.VISIBLE
        (idleButtons.layoutParams as ViewGroup.MarginLayoutParams).topMargin = px(if (compact) 12f else 24f)
        idleValue.layoutParams.height = if (compact) {
            val others = (if (mini) 0f else IDLE_LABEL_DP) + 12f + ROUND_BUTTON_DP + 8f
            px((content - others).coerceIn(IDLE_VALUE_MIN_DP, IDLE_VALUE_DP))
        } else {
            px(IDLE_VALUE_DP)
        }
        root.requestLayout()
    }

    private fun onPrimaryClicked() {
        if (!debounce()) return
        val w = WorkoutSession.workout
        when {
            w == null -> onStartClicked()
            w.state.resting -> WorkoutSession.nextSet()
            else -> WorkoutSession.finishSet()
        }
    }

    private fun onStartClicked() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // 通知权限只影响通知栏进度；无论是否授权都会开始训练
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            WorkoutSession.start(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && WorkoutSession.workout == null) WorkoutSession.start(this)
    }

    /** 同一位置的按钮会随阶段变化（完成本组 → 开始下一组），忽略过快的连点，防止误跳过休息。 */
    private fun debounce(): Boolean {
        val now = WorkoutSession.now()
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return false
        lastActionAt = now
        return true
    }

    /** 结束确认；对话框显示期间计时照常进行。 */
    private fun confirmEnd() {
        val w = WorkoutSession.workout ?: return
        if (endDialog?.isShowing == true) return
        val elapsed = TimeFormat.hhmmss(w.totalElapsedMs(WorkoutSession.now()))
        endDialog = AlertDialog.Builder(this)
            .setTitle(R.string.end_title)
            .setMessage(getString(R.string.end_message, w.exercisesDone, w.state.completedSets, elapsed))
            .setPositiveButton(R.string.end_ok) { _, _ -> WorkoutSession.stop() }
            .setNegativeButton(R.string.end_cancel, null)
            .show()
    }

    /** 训练中拦截返回手势，改为确认是否结束训练。 */
    private fun setBackIntercept(on: Boolean) {
        if (on == backIntercepting) return
        backIntercepting = on
        if (on) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
    }

    /** 训练中保持屏幕常亮，方便随时看计时。 */
    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val STATE_END_DIALOG = "end_dialog_showing"
        const val ACTION_DEBOUNCE_MS = 700L
        const val TIMER_TEXT_RATIO = 0.23f

        // 布局切换阈值（可用高度，dp）。竖排时圆环以外约占 314dp（顶栏 64 + 动作/组 ~90 + 组间休息 84 + 底部按钮 76），
        // 低于 520dp 圆环会小于 ~200dp，不如横排；横排时圆环以外约占 140dp，低于 300dp 再收起顶栏
        const val COMPACT_BELOW_DP = 520f
        const val MINI_BELOW_DP = 300f
        const val COMPACT_RING_WEIGHT = 1.3f
        const val TOP_BAR_DP = 64f
        const val BOTTOM_BAR_DP = 76f
        const val IDLE_LABEL_DP = 18f
        const val ROUND_BUTTON_DP = 64f
        const val IDLE_VALUE_DP = 128f
        const val IDLE_VALUE_MIN_DP = 48f
    }
}
