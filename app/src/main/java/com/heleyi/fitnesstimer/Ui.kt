package com.heleyi.fitnesstimer

import android.view.View
import android.view.WindowInsets
import android.widget.TextView

/** 文本相同时不重复设置，避免无障碍实时区域重复播报。 */
fun TextView.setTextIfChanged(value: CharSequence) {
    if (text.toString() != value.toString()) text = value
}

/** 全面屏：把系统栏与刘海区域尺寸叠加到 View 原有 padding 上，避免内容被遮挡。 */
fun View.applySystemBarInsetsAsPadding() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
    requestApplyInsets()
}
