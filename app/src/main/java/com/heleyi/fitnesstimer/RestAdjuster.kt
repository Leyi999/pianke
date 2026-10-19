package com.heleyi.fitnesstimer

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/** “−  01:30  +” 组间休息调节：每次 15 秒，按住可连续调节。 */
class RestAdjuster(
    private val minus: View,
    private val value: TextView,
    private val plus: View,
    private val onChanged: (Int) -> Unit,
) {
    private var seconds = DEFAULT

    init {
        minus.contentDescription = value.resources.getString(R.string.cd_rest_decrease)
        plus.contentDescription = value.resources.getString(R.string.cd_rest_increase)
        attachRepeatingClick(minus) { change(seconds - STEP) }
        attachRepeatingClick(plus) { change(seconds + STEP) }
    }

    fun show(newSeconds: Int) {
        seconds = newSeconds
        value.text = TimeFormat.mmss(newSeconds.toLong())
        value.contentDescription = value.resources.getString(
            R.string.cd_rest_value, TimeFormat.human(value.resources, newSeconds.toLong()),
        )
        minus.isEnabled = newSeconds > MIN
        plus.isEnabled = newSeconds < MAX
    }

    private fun change(target: Int) {
        val v = (target / STEP * STEP).coerceIn(MIN, MAX) // 对齐到 15 秒整数倍
        if (v != seconds) onChanged(v)
    }

    companion object {
        const val STEP = 15
        const val MIN = 15
        const val MAX = 600
        const val DEFAULT = 90
    }
}


private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 100L

/**
 * 点按执行一次；按住不放时连续执行。触摸事件仍交给 View 自身处理（按压态、波纹、performClick），
 * 因此 TalkBack 双击等无障碍点击照常可用。
 */
@SuppressLint("ClickableViewAccessibility")
private fun attachRepeatingClick(view: View, action: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var repeated = false
    val repeater = object : Runnable {
        override fun run() {
            if (!view.isEnabled || !view.isPressed) return
            repeated = true
            action()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }
    view.setOnClickListener {
        // 连发结束时松手产生的那次点击不再重复执行
        if (repeated) repeated = false else action()
    }
    view.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                repeated = false
                handler.postDelayed(repeater, REPEAT_DELAY_MS)
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeater)
                if (!v.isPressed) repeated = false // 手指移出按钮后松开，不会产生点击
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeater)
                repeated = false
            }
        }
        false
    }
}
