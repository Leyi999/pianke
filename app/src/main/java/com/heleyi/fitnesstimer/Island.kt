package com.heleyi.fitnesstimer

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 小米超级岛 / 焦点通知（HyperOS）：在通知 extras 里附加 miui.focus.param，系统据此在超级岛、锁屏、
 * 息屏展示训练进度。非小米设备或没有焦点通知权限时会被忽略，退化为普通通知。
 *
 * 信息随岛的形态逐级增加：
 * - 小岛：阶段图标（休息 / 超时外圈带进度环）
 * - 大岛：阶段图标 ｜ 计时
 * - 展开态 / 通知卡片：大号计时 · 杠铃 · 第几组，右侧两个按钮：⏭ 下一个动作，✓ 完成本组 / ▶ 开始下一组
 *   （休息中 ▶ 外圈就是倒计时圆环）
 *
 * 杠铃代表“这一组”：杠中间的数字是第几个动作，两侧杠铃片数是第几组（第 4 组起都是 4 片），越练越“重”。
 * 训练中大岛显示本组的杠铃；休息中大岛是沙漏，外圈进度环随剩余时间收缩，展开后的杠铃是下一组；
 * 休息超时是闪电，满圈。三个阶段各用一种颜色：训练 荧光绿、休息 冰蓝、超时 丁香紫（不用红色，避免紧迫感），
 * 计时数字也按阶段着色。系统计时器在大岛上只能画成白色，所以大岛的计时是文字，由应用亮屏时每秒刷新
 * （关闭文字切换动效，否则整串数字一起闪）；展开态的大号计时用系统计时器（等宽数字，变化的那一位滚动），
 * 切换阶段时的颜色处理见 [param] 的 recolor。
 */
object Island {

    const val KEY_PARAM = "miui.focus.param"

    private const val PICS = "miui.focus.pics"
    private const val ACTIONS = "miui.focus.actions"
    private const val PIC = "miui.focus.pic_"

    /** 杠铃（应用绘制）：深色背景用 / 浅色通知卡片用。 */
    private const val PIC_BAR = PIC + "bar"
    private const val PIC_BAR_LIGHT = PIC + "bar_light"

    /** 大岛左侧：休息 / 超时的阶段图标 + 进度环（应用绘制）。 */
    private const val PIC_RING = PIC + "ring"
    private const val ACTION_PRIMARY = "miui.focus.action_primary"
    private const val ACTION_EXERCISE = "miui.focus.action_exercise"
    private const val BTN_DONE = PIC + "btn_done"
    private const val BTN_NEXT = PIC + "btn_next"
    private const val BTN_NEXT_LIGHT = PIC + "btn_next_light"
    private const val BTN_GO = PIC + "btn_go"
    private const val BTN_SKIP = PIC + "btn_skip"
    private const val BTN_SKIP_LIGHT = PIC + "btn_skip_light"
    private const val BTN_BACK = PIC + "btn_back"
    private const val BTN_BACK_LIGHT = PIC + "btn_back_light"

    /** 岛默认 1 小时后消失，训练可能更久。 */
    private const val ISLAND_TIMEOUT_S = 6 * 60 * 60

    /** 图标的显示尺寸：HyperOS 大岛左侧（island_fix_icon_size）和展开态（focus_notify_function_icon_size）都是 20dp。 */
    private const val ICON_DP = 20f

    // 杠铃图标的几何参数，以 24×24 为设计网格
    private const val DIGIT_H = 9.6f // 中间数字的字形高度
    private const val DIGIT_MARGIN = 0.7f // 数字与内侧杠铃片的间距
    private const val BAR_T = 1.8f // 杠铃杆粗细
    private const val PLATE_W = 1.4f // 杠铃片宽度
    private const val PLATE_GAP = 0.6f // 杠铃片间距
    private val PLATE_H = floatArrayOf(16f, 13f, 10f, 7.5f) // 由内到外各片高度，最多 4 片

    /** 岛上的三个阶段。 */
    enum class Phase(
        val key: String,
        /** 深色背景（岛、深色通知卡片）用的颜色，与 colors.xml 中的 volt / ice / lilac 一致。 */
        val color: String,
        /** 浅色通知卡片用的深一档颜色。 */
        val colorLight: String,
        val label: Int,
    ) {
        SET("set", "#CCFF00", "#6B9900", R.string.phase_set),
        REST("rest", "#5AC8FA", "#1E88C8", R.string.phase_rest),
        OVER("over", "#A78BFA", "#6C44E4", R.string.phase_over);

        /** 进度环未走到的部分（底圈）：同色 25% 不透明度。 */
        val track: String get() = "#40" + color.drop(1)
        val trackLight: String get() = "#40" + colorLight.drop(1)
    }

    /** 某一时刻岛上显示的内容。计时相关的时间都换算成系统时间毫秒。 */
    class Frame(
        val phase: Phase,
        /** 大岛上的计时文字，与应用内一致：训练中 “01:23”，休息 “00:45”（剩余），超时 “+00:12”。 */
        val time: String,
        /** 进度环 0–100（休息 / 超时）。 */
        val progress: Int,
        /** 进度环的精确比例 0–1，用于绘制大岛左侧的圆环。 */
        val fraction: Float,
        /** 杠铃代表的那一组：训练中为本组，休息中 / 超时为下一组。 */
        val exercise: Int,
        val set: Int,
        /** 当前动作序号（“回到动作 N”）。 */
        val currentExercise: Int,
        /** 休息中 / 超时：下一组已换到下个动作。 */
        val nextExercise: Boolean,
        /** 计时基准：训练中为本组开始，休息中为休息结束（见 [frame]），超时为休息结束。 */
        val timerWhen: Long,
        /** 计时总长：休息中为组间休息时长，其余为 0（正计时）。 */
        val timerTotal: Long,
        /** 与 [timerWhen] 同一时间基准的“当前时刻”，系统据此校准计时。 */
        val timerNow: Long,
        /** 本次更新让岛自动展开并排到最前（休息刚结束时）。 */
        val expand: Boolean,
    ) {
        /** 小岛、息屏、状态栏用的阶段图标：训练中为杠铃，休息 / 超时为沙漏 / 闪电。 */
        val glyphPic: String get() = if (phase == Phase.SET) PIC_BAR else PIC + phase.key
    }

    /** @param wallOffset 系统时间 − elapsedRealtime，用于把计时基准换算成系统时间 */
    fun frame(w: Workout, now: Long, wallOffset: Long, expand: Boolean): Frame {
        val s = w.state
        val phase = phase(w, now)
        val phaseStart = wallOffset + s.phaseStartedAt
        val restEnd = phaseStart + w.restMs
        val remaining = w.restRemainingMs(now)
        return Frame(
            phase = phase,
            time = TimeFormat.phaseTimer(w, now),
            progress = when (phase) {
                Phase.SET -> 0
                // 向上取整：最后一秒仍留一小段圆弧，归零时才消失
                Phase.REST -> ((remaining * 100L + w.restMs - 1L) / w.restMs).toInt().coerceIn(0, 100)
                Phase.OVER -> 100
            },
            fraction = when (phase) {
                Phase.SET -> 0f
                Phase.REST -> (remaining.toFloat() / w.restMs).coerceIn(0f, 1f)
                Phase.OVER -> 1f
            },
            exercise = w.upcomingExercise,
            set = w.upcomingSet,
            currentExercise = s.exercise,
            nextExercise = s.resting && s.nextNewExercise,
            timerWhen = when (phase) {
                Phase.SET -> phaseStart
                // 系统倒计时向下取整（剩 89.2 秒显示 01:29），推后 999 毫秒与应用内的向上取整（01:30）一致
                Phase.REST -> restEnd + 999L
                Phase.OVER -> restEnd
            },
            timerTotal = if (phase == Phase.REST) w.restMs else 0L,
            timerNow = wallOffset + now,
            expand = expand,
        )
    }

    private fun phase(w: Workout, now: Long): Phase = when {
        !w.state.resting -> Phase.SET
        w.isOvertime(now) -> Phase.OVER
        else -> Phase.REST
    }

    /** 岛上按钮点击后要执行的操作。 */
    class Intents(
        val finishSet: PendingIntent,
        /** 完成本组，休息后换到下个动作。 */
        val finishExercise: PendingIntent,
        val nextSet: PendingIntent,
        /** 休息中切换下一组是否换到下个动作（休息结束后直接开始下个动作）。 */
        val toggleExercise: PendingIntent,
    )

    /** 当前阶段用到的图片（miui.focus.pics）和按钮（miui.focus.actions）。 */
    fun extras(context: Context, f: Frame, intents: Intents): Bundle {
        val pics = Bundle()
        fun pic(key: String, res: Int) = pics.putParcelable(key, Icon.createWithResource(context, res))
        pics.putParcelable(PIC_BAR, Icon.createWithBitmap(barbell(context, f, f.phase.color)))
        pics.putParcelable(PIC_BAR_LIGHT, Icon.createWithBitmap(barbell(context, f, f.phase.colorLight)))
        if (f.phase != Phase.SET) {
            val glyph = if (f.phase == Phase.REST) R.drawable.ic_island_rest else R.drawable.ic_island_over
            pic(f.glyphPic, glyph)
            pics.putParcelable(PIC_RING, Icon.createWithBitmap(ringIcon(context, glyph, f)))
        }
        if (f.nextExercise) {
            pic(BTN_BACK, R.drawable.ic_island_btn_back)
            pic(BTN_BACK_LIGHT, R.drawable.ic_island_btn_back_light)
        } else {
            pic(BTN_SKIP, R.drawable.ic_island_btn_skip)
            pic(BTN_SKIP_LIGHT, R.drawable.ic_island_btn_skip_light)
        }
        when (f.phase) {
            Phase.SET -> pic(BTN_DONE, R.drawable.ic_island_btn_done)
            Phase.REST -> {
                pic(BTN_NEXT, R.drawable.ic_island_btn_next)
                pic(BTN_NEXT_LIGHT, R.drawable.ic_island_btn_next_light)
            }
            Phase.OVER -> pic(BTN_GO, R.drawable.ic_island_btn_go)
        }
        val resting = f.phase != Phase.SET
        val actions = Bundle().apply {
            putParcelable(
                ACTION_EXERCISE,
                action(exerciseTitle(context, f), if (resting) intents.toggleExercise else intents.finishExercise),
            )
            putParcelable(
                ACTION_PRIMARY,
                action(primaryTitle(context, f), if (resting) intents.nextSet else intents.finishSet),
            )
        }
        return Bundle().apply {
            putBundle(PICS, pics)
            putBundle(ACTIONS, actions)
        }
    }

    /**
     * @param reopen 阶段变化：即使用户之前划掉过岛也重新显示（默认划掉后同一通知 24 小时内不再上岛）
     * @param recolor 从别的阶段切到训练中 / 超时：本次计时改发一个数值相同、此刻起倒数的倒计时。
     *   展开态的系统计时器在倒计时时会装上逐位滚动效果，这个效果记住了当时的数字颜色，之后的正计时一直沿用
     *   （从休息切到训练，数字仍是蓝色）；系统只在收到倒计时时重建它，此时用的是新阶段的颜色。下一秒再发正计时。
     */
    fun param(context: Context, f: Frame, reopen: Boolean, recolor: Boolean = false): String {
        val p = f.phase
        fun pic(key: String) = JSONObject().put("type", 1).put("pic", key)
        // 展开态、通知卡片、锁屏用系统计时器
        val countdown = p == Phase.REST || recolor
        val timer = JSONObject()
            .put("timerType", if (countdown) -1 else 1)
            // recolor：倒计时终点 = 现在 + 已计时长，此刻显示的数值与正计时相同（阶段刚开始时就是 00:00）
            .put("timerWhen", if (recolor && p != Phase.REST) 2 * f.timerNow - f.timerWhen else f.timerWhen)
            .put("timerTotal", f.timerTotal)
            .put("timerSystemCurrent", f.timerNow)

        val small = if (p == Phase.SET) {
            JSONObject().put("picInfo", pic(PIC_BAR))
        } else {
            val ring = JSONObject()
                .put("progress", f.progress)
                .put("colorReach", p.color)
                .put("colorUnReach", p.track)
                .put("isCCW", true) // 从 12 点钟方向开始
            JSONObject().put("combinePicInfo", JSONObject().put("picInfo", pic(f.glyphPic)).put("progressInfo", ring))
        }
        val big = JSONObject()
            .put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", pic(if (p == Phase.SET) PIC_BAR else PIC_RING)))
            // 按阶段着色的计时文字；不设 turnAnim（文字切换动效会让整串数字一起闪）
            .put("textInfo", JSONObject().put("title", f.time).put("showHighlightColor", true))
        val position = if (p == Phase.SET) {
            context.getString(R.string.cd_current, f.exercise, f.set)
        } else {
            context.getString(R.string.cd_next, f.exercise, f.set)
        }
        val island = JSONObject()
            .put("islandProperty", 2) // 操作为主：点击岛展开，露出按钮
            .put("islandTimeout", ISLAND_TIMEOUT_S)
            .put("islandOrder", f.expand)
            .put("highlightColor", p.color)
            .put("smallIslandArea", small)
            .put("bigIslandArea", big)
            .put("appContentDescription", context.getString(R.string.island_cd, context.getString(p.label), position))

        // 展开态：大号计时（按阶段着色）· 杠铃 · 第几组（type=1：不显示辅助文本 1）
        val highlight = JSONObject()
            .put("type", 1)
            .put("picFunction", PIC_BAR_LIGHT)
            .put("picFunctionDark", PIC_BAR)
            .put("subContent", setLabel(context, f))
            .put("timerInfo", timer)
            .put("colorTitle", p.colorLight)
            .put("colorTitleDark", p.color)

        // 左：⏭ 下一个动作（已换到下个动作时为 ⏮ 回到当前动作）；右：本阶段的主操作，位置与只有一个按钮时相同
        val exercise = if (f.nextExercise) {
            button(ACTION_EXERCISE, BTN_BACK_LIGHT, BTN_BACK, exerciseTitle(context, f))
        } else {
            button(ACTION_EXERCISE, BTN_SKIP_LIGHT, BTN_SKIP, exerciseTitle(context, f))
        }
        val primary = when (p) {
            Phase.SET -> button(ACTION_PRIMARY, BTN_DONE, BTN_DONE, primaryTitle(context, f))
            Phase.REST -> button(ACTION_PRIMARY, BTN_NEXT_LIGHT, BTN_NEXT, primaryTitle(context, f))
                .put("type", 1) // 进度按钮：外圈显示休息倒计时
                .put(
                    "progressInfo",
                    JSONObject()
                        .put("progress", f.progress)
                        .put("isCCW", true)
                        // 按钮进度环：colorProgress 是已走部分，colorProgressEnd 是底圈；深色（岛展开态、深色通知卡片）
                        // 读 *Dark。不设时系统用占位色（桃红进度 + 青绿底圈），与休息的冰蓝不搭
                        .put("colorProgress", p.colorLight)
                        .put("colorProgressEnd", p.trackLight)
                        .put("colorProgressDark", p.color)
                        .put("colorProgressEndDark", p.track),
                )
            Phase.OVER -> button(ACTION_PRIMARY, BTN_GO, BTN_GO, primaryTitle(context, f))
        }

        val label = context.getString(p.label)
        val v2 = JSONObject()
            .put("protocol", 1)
            .put("business", "fitness_timer")
            .put("updatable", true)
            .put("reopen", if (reopen) "reopen" else "close")
            .put("enableFloat", f.expand)
            .put("islandFirstFloat", false)
            .put("filterWhenNoPermission", false)
            .put("ticker", label)
            .put("tickerPic", f.glyphPic)
            .put("aodTitle", label)
            .put("aodPic", f.glyphPic)
            .put("param_island", island)
            .put("highlightInfo", highlight)
            .put("actions", JSONArray().put(exercise).put(primary))
        return JSONObject().put("param_v2", v2).toString()
    }

    private fun iconPx(context: Context): Int = (ICON_DP * context.resources.displayMetrics.density).roundToInt()

    /** 杠铃：杠中间是第几个动作，两侧杠铃片数 = 第几组（最多 4 片，由内到外逐片变矮）。 */
    private fun barbell(context: Context, f: Frame, color: String): Bitmap {
        val size = iconPx(context)
        val s = size.toFloat()
        val u = s / 24f
        val mid = s / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            textSize = 100f
        }
        // 数字：按 “8” 的字形高度定字号；两位数缩小到与一个数字同宽，杠铃片位置不随动作序号变化
        val text = f.exercise.toString()
        val eight = Rect().also { paint.getTextBounds("8", 0, 1, it) }
        val ink = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        var k = DIGIT_H * u / eight.height()
        val slot = eight.width() * k
        if (ink.width() * k > slot) k = slot / ink.width()
        paint.textSize = 100f * k
        canvas.drawText(text, mid - ink.exactCenterX() * k, mid - ink.exactCenterY() * k, paint)
        // 杠铃杆：两端到数字两侧，中间留给数字
        val inner = mid - slot / 2f - DIGIT_MARGIN * u
        val bar = BAR_T / 2f * u
        canvas.drawRoundRect(0.5f * u, mid - bar, inner, mid + bar, bar, bar, paint)
        canvas.drawRoundRect(s - inner, mid - bar, s - 0.5f * u, mid + bar, bar, bar, paint)
        // 杠铃片
        val r = PLATE_W / 2f * u
        for (i in 0 until f.set.coerceIn(1, PLATE_H.size)) {
            val x1 = inner - i * (PLATE_W + PLATE_GAP) * u
            val x0 = x1 - PLATE_W * u
            val h = PLATE_H[i] / 2f * u
            canvas.drawRoundRect(x0, mid - h, x1, mid + h, r, r, paint)
            canvas.drawRoundRect(s - x1, mid - h, s - x0, mid + h, r, r, paint)
        }
        return bitmap
    }

    /** 大岛左侧图标：阶段图标外加一圈进度环，样式同小岛的系统圆环（12 点钟起顺时针，线宽为直径的 1/10）。 */
    private fun ringIcon(context: Context, glyph: Int, f: Frame): Bitmap {
        val size = iconPx(context)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = size / 10f
        val oval = RectF(stroke / 2f, stroke / 2f, size - stroke / 2f, size - stroke / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor(f.phase.track)
        }
        canvas.drawOval(oval, paint)
        paint.color = Color.parseColor(f.phase.color)
        canvas.drawArc(oval, -90f, 360f * f.fraction, false, paint)
        val g = (size * 0.7f).roundToInt()
        val o = (size - g) / 2
        checkNotNull(context.getDrawable(glyph)).apply { setBounds(o, o, o + g, o + g) }.draw(canvas)
        return bitmap
    }

    /** 展开态的组数文字：“第3组”；下一组换到下个动作时为“下个动作”。 */
    private fun setLabel(context: Context, f: Frame): String =
        if (f.nextExercise) context.getString(R.string.label_next_exercise) else context.getString(R.string.island_set, f.set)

    private fun exerciseTitle(context: Context, f: Frame): String =
        if (f.nextExercise) {
            context.getString(R.string.back_to_exercise_n, f.currentExercise)
        } else {
            context.getString(R.string.next_exercise)
        }

    private fun primaryTitle(context: Context, f: Frame): String =
        if (f.phase == Phase.SET) context.getString(R.string.finish_set) else context.getString(R.string.start_set_n, f.set)

    /** 按钮图标、无障碍朗读文字由 JSON 指定（actionIcon 会替换 Action 自带的图标）。 */
    private fun button(action: String, icon: String, iconDark: String, title: String) = JSONObject()
        .put("type", 0)
        .put("action", action)
        .put("actionIcon", icon)
        .put("actionIconDark", iconDark)
        .put("actionTitle", title)

    /** Action 只提供点击后的 PendingIntent，图标由 JSON 中的 actionIcon 指定。 */
    private fun action(title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(null as Icon?, title, intent).build()

    /** 焦点通知协议版本：3 及以上支持超级岛（HyperOS 3+）；非小米设备为 0。 */
    fun protocol(context: Context): Int =
        Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)

    /** 当前应用是否有焦点通知（超级岛）权限。跨进程查询，较慢。 */
    fun hasPermission(context: Context): Boolean = try {
        context.contentResolver.call(
            Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null,
            Bundle().apply { putString("package", context.packageName) },
        )?.getBoolean("canShowFocus", false) == true
    } catch (e: Exception) {
        false
    }
}
