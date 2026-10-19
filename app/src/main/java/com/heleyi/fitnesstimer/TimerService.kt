package com.heleyi.fitnesstimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 训练期间的前台服务：切到后台/锁屏后进程继续存活；休息倒计时期间持有 CPU 唤醒锁，归零时准时提醒；
 * 通知栏显示当前动作、组数与计时（秒数由系统计时器走动）。Android 16+ 发“实时更新”，HyperOS 会把它放上超级岛；
 * 否则在小米超级岛可用时发自定义岛。两种岛都在亮屏期间每秒刷新计时；岛上（或展开后的通知卡片上）的按钮
 * 可以直接完成本组 / 开始下一组 / 换到下一个动作。训练结束时移除通知并停止。
 */
class TimerService : Service(), WorkoutSession.Listener {

    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockDeadline = -1L
    private var lastNotificationKey: String? = null
    private var lastOvertime: Boolean? = null
    private var inForeground = false
    private var running = false
    private var lastStartId = 0

    /**
     * Android 16+ 且允许实时更新：申请“实时更新”。HyperOS 会把实时更新通知自动放上超级岛（系统样式：
     * 左边是通知小图标，右边是 shortCriticalText，展开后是标准通知卡片），不需要小米的超级岛授权；
     * 系统会丢弃通知里的自定义岛参数，所以这时不再附带。
     */
    private var liveUpdate = false

    /**
     * 自定义超级岛（不走实时更新，且 HyperOS 3+、有焦点通知权限）。国内版 HyperOS 还要求应用通过小米的
     * 超级岛授权，否则系统退化为普通通知。
     */
    private var island = false

    /** 系统时间 − elapsedRealtime；阶段变化时取一次，同一阶段内计时基准不随每秒刷新抖动。 */
    private var wallOffset = 0L

    /** 上一次发出的岛阶段，用于判断是否要让系统按新颜色重建展开态计时器，见 [buildNotification]。 */
    private var lastIslandPhase: Island.Phase? = null

    private val contentIntent: PendingIntent by lazy {
        val open = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private val islandIntents: Island.Intents by lazy {
        Island.Intents(
            finishSet = actionIntent(ACTION_FINISH_SET, 1),
            finishExercise = actionIntent(ACTION_FINISH_EXERCISE, 3),
            nextSet = actionIntent(ACTION_NEXT_SET, 2),
            toggleExercise = actionIntent(ACTION_TOGGLE_EXERCISE, 4),
        )
    }
    private var lastIslandActionAt = Long.MIN_VALUE / 2

    /** 亮屏：立即刷新一次并恢复每秒刷新。熄屏：实时更新把计时换成阶段文字，息屏显示不会停在旧时间上。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (running) refresh(force = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        createChannel()
        liveUpdate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            notificationManager.canPostPromotedNotifications()
        val protocol = Island.protocol(this)
        island = !liveUpdate && protocol >= 3 && Island.hasPermission(this)
        if (island || liveUpdate) {
            // 熄屏时岛不可见，不再每秒刷新
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        }
        Log.i(TAG, "focus protocol=$protocol liveUpdate=$liveUpdate island=$island")
    }

    override fun onDestroy() {
        if (island || liveUpdate) unregisterReceiver(screenReceiver)
        WorkoutSession.removeListener(this)
        releaseWakeLock()
        super.onDestroy()
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val action = intent?.action
        if (action != null) {
            onIslandAction(action)
            if (running) return START_NOT_STICKY
        }
        // startForegroundService() 之后必须尽快调用 startForeground()
        try {
            wallOffset = System.currentTimeMillis() - WorkoutSession.now()
            lastIslandPhase = null
            val notification = buildNotification(WorkoutSession.workout, WorkoutSession.now())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForeground failed", e) // 计时照常进行，只是没有通知栏进度
        }
        running = true
        lastNotificationKey = null
        lastOvertime = null
        WorkoutSession.addListener(this)
        onWorkoutChanged()
        return START_NOT_STICKY
    }

    /**
     * 岛上的按钮。按钮会在原位随阶段切换（✓ → ▶，⏭ → ⏮），阶段刚切换或刚点过按钮时忽略点击，
     * 防止连点误跳过休息、把“下一个动作”切过去又切回来。
     */
    private fun onIslandAction(action: String) {
        val w = WorkoutSession.workout ?: return
        val now = WorkoutSession.now()
        if (now - w.state.phaseStartedAt < ACTION_GUARD_MS || now - lastIslandActionAt < ACTION_GUARD_MS) return
        lastIslandActionAt = now
        when (action) {
            ACTION_FINISH_SET -> WorkoutSession.finishSet()
            ACTION_FINISH_EXERCISE -> WorkoutSession.finishExercise()
            ACTION_NEXT_SET -> WorkoutSession.nextSet()
            ACTION_TOGGLE_EXERCISE -> WorkoutSession.nextExercise()
        }
    }

    override fun onWorkoutChanged() = refresh(force = false)

    /** @param force 亮屏 / 熄屏时即使阶段没变也重发一次（熄屏后不再每秒刷新）。 */
    private fun refresh(force: Boolean) {
        val w = WorkoutSession.workout ?: return shutdown()
        val now = WorkoutSession.now()
        updateWakeLock(w, now)
        val overtime = w.isOvertime(now)
        val key = "${w.state}|$overtime|${w.restMs}"
        val changed = key != lastNotificationKey
        // 通知栏和展开态的计时由系统计时器走动，只在阶段、序号或休息时长变化时更新；
        // 岛上的计时文字和进度环由应用提供，亮屏时每秒刷新
        val ticking = (island || liveUpdate) && powerManager.isInteractive
        if (!changed && !ticking && !force) return
        var expand = false
        if (changed) {
            lastNotificationKey = key
            // 休息刚结束（倒计时 → 超时）的那次更新让超级岛自动展开
            expand = overtime && lastOvertime == false
            lastOvertime = overtime
            wallOffset = System.currentTimeMillis() - now
        }
        // 用户划掉岛后，下次阶段变化时让岛重新出现（每秒的进度刷新不会把划掉的岛拉回来）
        notificationManager.notify(NOTIFICATION_ID, buildNotification(w, now, expand, reopen = changed))
    }

    private fun shutdown() {
        WorkoutSession.removeListener(this)
        releaseWakeLock()
        running = false
        if (inForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf(lastStartId) // 若期间又开始了新训练，不会误停
    }


    /** 只有休息倒计时期间需要 CPU 保持唤醒（为了归零时准时提醒）。 */
    private fun updateWakeLock(w: Workout, now: Long) {
        val remaining = if (w.state.resting) w.restRemainingMs(now) else 0L
        if (remaining <= 0L) {
            releaseWakeLock()
            return
        }
        val lock = wakeLock ?: powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FitnessTimer:rest")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        val deadline = w.state.phaseStartedAt + w.restMs
        if (!lock.isHeld || deadline != wakeLockDeadline) {
            lock.acquire(remaining + WAKE_LOCK_MARGIN_MS) // 超时仅作兜底
            wakeLockDeadline = deadline
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLockDeadline = -1L
    }

    private fun createChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW, // 静默通知：提醒由 Alerts 按开关单独发出
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, TimerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )


    private fun buildNotification(w: Workout?, now: Long, expand: Boolean = false, reopen: Boolean = true): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(getColor(R.color.accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
        builder.setCategory(Notification.CATEGORY_WORKOUT)
        builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (w == null) return builder.setContentTitle(getString(R.string.app_name)).build()

        val s = w.state
        val overtime = w.isOvertime(now)
        val title = when {
            !s.resting -> getString(R.string.notif_set, s.exercise, s.set)
            overtime -> getString(R.string.notif_over, w.upcomingExercise, w.upcomingSet)
            else -> getString(R.string.notif_rest, w.upcomingExercise, w.upcomingSet)
        }
        val caption = when {
            !s.resting -> R.string.caption_set
            overtime -> R.string.caption_over
            else -> R.string.caption_rest
        }
        // 计时基准换算成系统时间，由通知栏自行走秒
        val frame = Island.frame(w, now, wallOffset, expand)
        builder.setContentTitle(title)
            .setContentText(getString(caption))
            .setShowWhen(true)
            .setWhen(frame.timerWhen)
            .setUsesChronometer(true)
            .setChronometerCountDown(frame.phase == Island.Phase.REST)
        if (liveUpdate) return liveUpdateNotification(builder, frame)
        builder.addExtras(Island.extras(this, frame, islandIntents))
        val notification = builder.build()
        // 切到训练中 / 超时（包括新训练的第一帧）：让系统按新颜色重建展开态计时器的逐位滚动效果（见 Island.param），
        // 训练中的计时从第一组起就会滚动。只在超级岛每秒刷新时这样做，下一秒的刷新会换回正计时。
        val recolor = island && frame.phase != Island.Phase.REST && frame.phase != lastIslandPhase
        lastIslandPhase = frame.phase
        // 小米超级岛 / 焦点通知：岛、锁屏、息屏展示计时；休息结束时自动展开提醒
        notification.extras.putString(Island.KEY_PARAM, Island.param(this, frame, reopen, recolor))
        return notification
    }

    /**
     * 实时更新（与 NotificationCompat.setRequestPromotedOngoing 相同的 extra）：状态栏胶囊、锁屏顶部持续显示，
     * HyperOS 上由系统生成超级岛。大岛左边的通知小图标换成本组的杠铃 / 带进度环的阶段图标，右边是计时；
     * 熄屏后不再每秒刷新，计时换成阶段文字（息屏显示用的也是这段文字）。
     */
    private fun liveUpdateNotification(builder: Notification.Builder, f: Island.Frame): Notification {
        builder.setSmallIcon(Island.smallIcon(this, f))
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setShortCriticalText(if (powerManager.isInteractive) f.time else getString(f.phase.label))
        }
        Island.standardActions(this, f, islandIntents).forEach { builder.addAction(it) }
        return builder.build()
    }


    companion object {
        private const val TAG = "TimerService"
        private const val CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_MARGIN_MS = 30_000L
        private const val ACTION_GUARD_MS = 700L
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val ACTION_FINISH_SET = "com.heleyi.fitnesstimer.action.FINISH_SET"
        private const val ACTION_FINISH_EXERCISE = "com.heleyi.fitnesstimer.action.FINISH_EXERCISE"
        private const val ACTION_NEXT_SET = "com.heleyi.fitnesstimer.action.NEXT_SET"
        private const val ACTION_TOGGLE_EXERCISE = "com.heleyi.fitnesstimer.action.TOGGLE_EXERCISE"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TimerService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "startForegroundService failed", e)
            }
        }
    }
}
