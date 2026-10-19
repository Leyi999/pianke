package com.heleyi.fitnesstimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 休息倒计时归零时的提醒。“响铃”“震动”各自读取独立开关，互不影响；每次提醒时实时读取，
 * 训练中切换开关从下一次提醒起生效。提示音在本地合成，不需要网络或音频文件。
 */
object Alerts {

    private const val TAG = "Alerts"
    private const val SAMPLE_RATE = 44_100
    private val handler = Handler(Looper.getMainLooper())
    private var pcm: ByteBuffer? = null

    fun fire(context: Context) {
        val prefs = Prefs(context)
        if (prefs.soundEnabled) beep(context)
        if (prefs.vibrateEnabled) buzz(context)
    }

    /** 播放一次提示音；失败只记日志，不影响计时。 */
    fun beep(context: Context) {
        try {
            playSound(context)
        } catch (e: RuntimeException) {
            Log.w(TAG, "play sound failed", e)
        }
    }


    /** 震动一次；失败只记日志，不影响计时。 */
    fun buzz(context: Context) {
        try {
            vibrate(context)
        } catch (e: RuntimeException) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    fun hasVibrator(context: Context): Boolean = vibrator(context)?.hasVibrator() == true

    private fun vibrator(context: Context): Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    /** 震—停—震，共 850 毫秒；使用“闹钟”用途，手机静音时也会震动。 */
    private fun vibrate(context: Context) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1)
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    }


    /** 媒体用途：戴耳机时只在耳机里响，并临时压低正在播放的音乐。 */
    private fun playSound(context: Context) {
        val data = pcm ?: synthesize().also { pcm = it }
        val sizeInBytes = data.capacity()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(sizeInBytes)
            .build()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        try {
            // duplicate() 共享数据、读取位置从 0 开始，缓存的 PCM 可反复使用
            val written = track.write(data.duplicate(), sizeInBytes, AudioTrack.WRITE_BLOCKING)
            check(written == sizeInBytes) { "AudioTrack write failed: $written" }
            audioManager?.requestAudioFocus(focus)
            track.play()
        } catch (e: RuntimeException) {
            track.release()
            audioManager?.abandonAudioFocusRequest(focus)
            throw e
        }
        handler.postDelayed({
            track.release()
            audioManager?.abandonAudioFocusRequest(focus)
        }, sizeInBytes / 2 * 1000L / SAMPLE_RATE + 300L)
    }


    /**
     * 合成提示音：柔和的拨弦电钢琴音色，D 大三和弦快速上行（D5 → F#5 → A5），约 1.3 秒。
     * 起音瞬间带少量泛音、随即变成圆润的正弦并自然衰减，再叠两次短反射增加空间感，听感干净不刺耳。
     */
    private fun synthesize(): ByteBuffer {
        val freqs = doubleArrayOf(587.33, 739.99, 880.0)
        val onsets = doubleArrayOf(0.0, 0.09, 0.18)
        val decays = doubleArrayOf(0.3, 0.34, 0.7)
        val gains = doubleArrayOf(0.75, 0.85, 1.0)
        val n = (SAMPLE_RATE * 1.3).toInt()
        val dry = DoubleArray(n)
        for (k in freqs.indices) {
            val start = (onsets[k] * SAMPLE_RATE).toInt()
            val omega = 2.0 * PI * freqs[k] / SAMPLE_RATE
            for (i in start until n) {
                val t = (i - start).toDouble() / SAMPLE_RATE
                val env = min(1.0, t / 0.004) * exp(-t / decays[k])
                val phase = omega * (i - start)
                val index = 1.2 * exp(-t / 0.04) // FM 调制深度：起音明亮，40ms 内变圆润
                dry[i] += gains[k] * env * (sin(phase + index * sin(phase)) + 0.2 * sin(phase * 0.5))
            }
        }
        val d1 = (0.067 * SAMPLE_RATE).toInt()
        val d2 = (0.113 * SAMPLE_RATE).toInt()
        val wet = DoubleArray(n) { i ->
            dry[i] + (if (i >= d1) 0.22 * dry[i - d1] else 0.0) + (if (i >= d2) 0.12 * dry[i - d2] else 0.0)
        }
        val peak = wet.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val fade = (0.06 * SAMPLE_RATE).toInt()
        val out = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            var v = wet[i] / peak * 0.85
            if (i > n - fade) v *= (n - i).toDouble() / fade
            out.putShort(i * 2, (v * Short.MAX_VALUE).toInt().toShort())
        }
        return out
    }
}
