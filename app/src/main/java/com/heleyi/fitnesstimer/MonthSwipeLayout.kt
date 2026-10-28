package com.heleyi.fitnesstimer

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 月历卡片（view_history_calendar 的根布局）：左右滑动切换月份，向左滑看下个月、向右滑看上个月。
 * 只接管明显的横向滑动，点日期、上下滚动列表不受影响。滑动只是箭头按钮的快捷方式，读屏用箭头按钮。
 */
class MonthSwipeLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    /** next = true：向左滑，看下个月。 */
    var onSwipe: ((next: Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    /** 从日期格子上开始的滑动：确认是横向滑动后接管过来，格子收到取消，不会误触点击。 */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return swiping
    }

    @SuppressLint("ClickableViewAccessibility") // 滑动只是箭头按钮的快捷方式，没有对应的点击
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && swiping) {
            val dx = ev.x - downX
            if (abs(dx) >= width * SWIPE_FRACTION) onSwipe?.invoke(dx < 0)
        }
        return true // 收下这次触摸才能接着收到滑动；上下滑动时列表照样会接管
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> if (!swiping) {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > touchSlop && dx > dy * 2) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true) // 横向滑动时不让列表抢走
                }
            }
        }
    }

    private companion object {
        /** 滑过卡片宽度的这个比例才切换月份。 */
        const val SWIPE_FRACTION = 0.15f
    }
}
