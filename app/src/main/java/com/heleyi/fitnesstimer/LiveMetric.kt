package com.heleyi.fitnesstimer

import android.annotation.SuppressLint
import android.app.Notification
import android.os.Build
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Android 17 的 Notification.MetricStyle：实时更新展开后（HyperOS 超级岛展开态、锁屏和通知栏的卡片）
 * 一行并排几个“标签 + 大号数值”，数值是系统文字（28sp 窄体等宽数字），颜色随卡片深浅自动切换，计时由系统走秒。
 *
 * 只放一个指标时系统改用 36sp 的单指标布局，HyperOS 锁屏卡片排不下（只给约 71dp 高，按钮盖住内容），
 * 所以至少放两个指标。编译用的 SDK 36 里还没有这些类（公开 API，API 37 起提供），所以用反射创建；
 * 系统没有时返回 null，调用方退回标准通知模板。
 */
object LiveMetric {

    private const val TAG = "LiveMetric"
    private const val API_37 = 37

    /** Notification.Metric.TimeDifference.FORMAT_CHRONOMETER：固定 “分:秒” 格式。 */
    private const val FORMAT_CHRONOMETER = 2

    /** 一个指标：标签 + 数值。 */
    sealed class Value {
        /** 计时；[zero] 是 elapsedRealtime 零点：正计时为开始时刻，倒计时为结束时刻。 */
        class Timer(val zero: Long, val countDown: Boolean) : Value()
        class Number(val value: Int) : Value()
    }

    private class Api(
        val newStyle: Constructor<*>,
        val addMetric: Method,
        val setCriticalMetric: Method,
        val newMetric: Constructor<*>,
        val forTimer: Method,
        val forStopwatch: Method,
        val newInt: Constructor<*>,
    )

    private val api: Api? by lazy { load() }

    @SuppressLint("PrivateApi") // API 37 的公开类，只是不在编译用的 SDK 36 里
    private fun load(): Api? {
        if (Build.VERSION.SDK_INT < API_37) return null
        return try {
            val style = Class.forName("android.app.Notification\$MetricStyle")
            val metric = Class.forName("android.app.Notification\$Metric")
            val value = Class.forName("android.app.Notification\$Metric\$MetricValue")
            val diff = Class.forName("android.app.Notification\$Metric\$TimeDifference")
            val fixedInt = Class.forName("android.app.Notification\$Metric\$FixedInt")
            val long = Long::class.javaPrimitiveType
            val int = Int::class.javaPrimitiveType
            Api(
                newStyle = style.getConstructor(),
                addMetric = style.getMethod("addMetric", metric),
                setCriticalMetric = style.getMethod("setCriticalMetric", int),
                newMetric = metric.getConstructor(value, CharSequence::class.java),
                forTimer = diff.getMethod("forTimer", long, int),
                forStopwatch = diff.getMethod("forStopwatch", long, int),
                newInt = fixedInt.getConstructor(int),
            )
        } catch (e: ReflectiveOperationException) {
            Log.i(TAG, "MetricStyle unavailable: $e")
            null
        }
    }

    /**
     * 按顺序并排的指标（标签 to 数值）；[critical] 是最重要的那个（系统在状态栏胶囊等小位置优先显示它）。
     * 系统没有 MetricStyle 时返回 null。
     */
    fun style(metrics: List<Pair<CharSequence, Value>>, critical: Int): Notification.Style? {
        val a = api ?: return null
        return try {
            val style = a.newStyle.newInstance()
            for ((label, v) in metrics) {
                val value = when (v) {
                    is Value.Timer ->
                        (if (v.countDown) a.forTimer else a.forStopwatch).invoke(null, v.zero, FORMAT_CHRONOMETER)
                    is Value.Number -> a.newInt.newInstance(v.value)
                }
                a.addMetric.invoke(style, a.newMetric.newInstance(value, label))
            }
            a.setCriticalMetric.invoke(style, critical)
            style as Notification.Style
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "MetricStyle failed: $e")
            null
        }
    }
}
