package com.heleyi.fitnesstimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 计时圆环（装饰用，可见时约 30 帧/秒平滑刷新）：
 * 训练中——光点每 60 秒绕行一圈；休息——圆弧随剩余时间收缩；超时——整圈呼吸闪烁。
 */
class RingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private enum class Mode { SWEEP, COUNTDOWN, OVERTIME }

    /** 圆环直径（像素）变化时回调，用于按比例设置中间计时数字的字号。 */
    var onDiameterChanged: ((Float) -> Unit)? = null

    private var mode = Mode.SWEEP
    private var ringColor = Color.WHITE
    private var startAt = 0L
    private var durationMs = 1L

    private val ringWidth = 9f * resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = context.getColor(R.color.ring_track)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tailShader: Shader? = null
    private val oval = RectF()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private val frame = Runnable { invalidate() } // 单一帧任务，避免重复叠加刷新


    fun showSweep(color: Int, startAt: Long) = setState(Mode.SWEEP, color, startAt, 1L)

    fun showCountdown(color: Int, startAt: Long, durationMs: Long) =
        setState(Mode.COUNTDOWN, color, startAt, durationMs)

    fun showOvertime(color: Int) = setState(Mode.OVERTIME, color, 0L, 1L)

    private fun setState(mode: Mode, color: Int, startAt: Long, durationMs: Long) {
        val colorChanged = color != ringColor
        this.mode = mode
        ringColor = color
        this.startAt = startAt
        this.durationMs = durationMs.coerceAtLeast(1L)
        if (colorChanged) updateShaders()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = ((min(w, h) - ringWidth) / 2f - 1f).coerceAtLeast(0f)
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        updateShaders()
        val diameter = radius * 2f
        post { onDiameterChanged?.invoke(diameter) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    private fun updateShaders() {
        dotPaint.color = ringColor
        if (radius <= 0f) return
        glowPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(withAlpha(ringColor, 0x24), withAlpha(ringColor, 0x0A), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        tailShader = SweepGradient(
            cx, cy, intArrayOf(Color.TRANSPARENT, ringColor), floatArrayOf(0f, TAIL_DEG / 360f),
        )
    }


    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        val now = SystemClock.elapsedRealtime()
        canvas.drawCircle(cx, cy, radius, glowPaint)
        canvas.drawCircle(cx, cy, radius, trackPaint)
        when (mode) {
            Mode.SWEEP -> drawSweep(canvas, now)
            Mode.COUNTDOWN -> drawCountdown(canvas, now)
            Mode.OVERTIME -> drawOvertime(canvas, now)
        }
        removeCallbacks(frame)
        if (isShown) postDelayed(frame, FRAME_MS) // 不可见时自动停止刷新
    }

    /** 秒表：光点每分钟绕一圈，身后拖一段渐隐尾迹。 */
    private fun drawSweep(canvas: Canvas, now: Long) {
        val head = (now - startAt).mod(60_000L) / 60_000f * 360f - 90f // 画布角度：0° 指向 3 点钟
        canvas.save()
        canvas.rotate(head - TAIL_DEG, cx, cy)
        arcPaint.shader = tailShader
        arcPaint.strokeCap = Paint.Cap.BUTT
        arcPaint.alpha = 255
        canvas.drawArc(oval, 0f, TAIL_DEG, false, arcPaint)
        canvas.restore()
        val rad = Math.toRadians(head.toDouble())
        val x = cx + radius * cos(rad).toFloat()
        val y = cy + radius * sin(rad).toFloat()
        canvas.drawCircle(x, y, ringWidth * 0.75f, dotPaint)
    }

    /** 休息倒计时：圆弧终点固定在 12 点，起点随剩余时间顺时针收拢。 */
    private fun drawCountdown(canvas: Canvas, now: Long) {
        val remaining = (durationMs - (now - startAt)).coerceIn(0L, durationMs)
        val sweep = 360f * remaining / durationMs
        if (sweep < 0.5f) return
        arcPaint.shader = null
        arcPaint.strokeCap = Paint.Cap.ROUND
        arcPaint.color = ringColor
        canvas.drawArc(oval, 270f - sweep, sweep, false, arcPaint)
    }


    /** 超时：整圈呼吸闪烁。 */
    private fun drawOvertime(canvas: Canvas, now: Long) {
        val t = (now % BREATH_MS) / BREATH_MS.toFloat()
        val level = 0.45f + 0.55f * (0.5f + 0.5f * cos(2.0 * PI * t).toFloat())
        arcPaint.shader = null
        arcPaint.color = ringColor
        arcPaint.alpha = (level * 255).toInt()
        canvas.drawCircle(cx, cy, radius, arcPaint)
    }

    private fun withAlpha(c: Int, alpha: Int): Int = (c and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        const val TAIL_DEG = 110f
        const val FRAME_MS = 33L
        const val BREATH_MS = 1600L
    }
}
