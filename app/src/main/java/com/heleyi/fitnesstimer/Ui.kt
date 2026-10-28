package com.heleyi.fitnesstimer

import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/** 文本相同时不重复设置，避免无障碍实时区域重复播报。 */
fun TextView.setTextIfChanged(value: CharSequence) {
    if (text.toString() != value.toString()) text = value
}

/** 读屏读出的操作名换成具体的说法（例如把长按读作“删除”）；只替换控件本来就有的操作。 */
class ActionLabels(vararg labels: Pair<Int, CharSequence>) : View.AccessibilityDelegate() {
    private val actions = labels.map { (id, label) -> AccessibilityNodeInfo.AccessibilityAction(id, label) }

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        val present = info.actionList.map { it.id }
        for (action in actions) if (action.id in present) info.addAction(action) // 同一 id 的操作会被替换
    }
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
