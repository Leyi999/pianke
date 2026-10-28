package com.heleyi.fitnesstimer

import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Space

/** 训练部位多选（结束确认框、记录详情）：芯片每行 [PER_ROW] 个、等宽，填进竖排的 [container]。 */
class PartPicker(container: LinearLayout, initial: Set<BodyPart>) {

    private val chips = LinkedHashMap<BodyPart, CheckBox>()

    val selected: Set<BodyPart> get() = chips.filterValues { it.isChecked }.keys.toSet()

    init {
        val context = container.context
        val gap = (GAP_DP * context.resources.displayMetrics.density).toInt()
        BodyPart.entries.chunked(PER_ROW).forEachIndexed { i, parts ->
            val row = LinearLayout(context)
            val rowParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) rowParams.topMargin = gap
            container.addView(row, rowParams)
            for (j in 0 until PER_ROW) {
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (j > 0) params.marginStart = gap
                val part = parts.getOrNull(j)
                if (part == null) {
                    row.addView(Space(context), params) // 最后一行补空位，芯片保持等宽
                    continue
                }
                val chip = CheckBox(context, null, 0, R.style.PartChip)
                chip.setText(part.label)
                chip.isChecked = part in initial
                row.addView(chip, params)
                chips[part] = chip
            }
        }
    }

    private companion object {
        const val PER_ROW = 4
        const val GAP_DP = 8f
    }
}
