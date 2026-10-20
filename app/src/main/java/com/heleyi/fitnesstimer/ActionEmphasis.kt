package com.heleyi.fitnesstimer

import android.app.Notification
import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * Android 17 的 Notification.Action.Builder.setEmphasisHint：实时更新的按钮是胶囊形，主按钮实心、次按钮描边。
 * 编译用的 SDK 36 里还没有这个方法（公开 API，API 37 起提供），所以用反射调用；没有时按钮保持系统默认样式。
 */
object ActionEmphasis {

    private const val TAG = "ActionEmphasis"
    private const val API_37 = 37

    /** Notification.Action.EMPHASIS_PRIMARY / EMPHASIS_SECONDARY */
    private const val PRIMARY = 1
    private const val SECONDARY = 2

    private val setEmphasisHint: Method? by lazy {
        if (Build.VERSION.SDK_INT < API_37) return@lazy null
        try {
            Notification.Action.Builder::class.java.getMethod("setEmphasisHint", Int::class.javaPrimitiveType)
        } catch (e: ReflectiveOperationException) {
            Log.i(TAG, "setEmphasisHint unavailable: $e")
            null
        }
    }

    fun apply(builder: Notification.Action.Builder, primary: Boolean): Notification.Action.Builder {
        val method = setEmphasisHint ?: return builder
        try {
            method.invoke(builder, if (primary) PRIMARY else SECONDARY)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "setEmphasisHint failed: $e")
        }
        return builder
    }
}
