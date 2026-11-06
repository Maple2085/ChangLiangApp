package com.changliang.screenon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 常亮服务：
 *   1. 在屏幕角落放一个 1×1 的透明悬浮窗，并给窗口加 FLAG_KEEP_SCREEN_ON；
 *   2. 用前台服务保住这个窗口，切到任何应用都不会被系统回收；
 *   3. 倒计时结束 / 用户点结束 → 移除悬浮窗，屏幕恢复系统休眠策略。
 *
 * 悬浮窗只有 1×1 像素，不可触摸，不会遮挡任何操作。
 */
class KeepOnService : Service() {

    companion object {
        const val ACTION_START = "com.changliang.screenon.action.START"
        const val ACTION_STOP = "com.changliang.screenon.action.STOP"
        const val EXTRA_MINUTES = "minutes"

        const val CHANNEL_ID = "keep_on_status"
        const val NOTIFICATION_ID = 3421

        private const val PREFS = "keep_on_service"
        private const val KEY_RUNNING = "running"
        private const val KEY_ALWAYS = "always"
        private const val KEY_END_AT = "end_at"
        private const val KEY_START_AT = "start_at"
        private const val KEY_TOTAL_MS = "total_ms"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var alwaysOn: Boolean = false
            private set

        @Volatile
        var endAt: Long = 0L
            private set

        @Volatile
        var startAt: Long = 0L
            private set

        @Volatile
        var totalMs: Long = 0L
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var completed = false

    private val finishRunnable = Runnable { finishByTimer() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopManually()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 30)
                begin(minutes)
                return START_STICKY
            }
        }

        // 系统在进程被杀后重启服务：能恢复就恢复，否则清掉状态。
        if (restoreIfAlive()) {
            return START_STICKY
        }
        clearPersistedState()
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉应用时，常亮要继续；用户可以在通知栏结束。
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        running = false
        alwaysOn = false
        endAt = 0L
        if (!completed) {
            notificationManager.cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    private fun begin(minutes: Int) {
        val safeMinutes = when {
            minutes < 0 -> -1
            minutes == 0 -> 30
            else -> minutes
        }
        val now = System.currentTimeMillis()
        startAt = now
        alwaysOn = safeMinutes < 0
        totalMs = if (alwaysOn) 0L else safeMinutes * 60_000L
        endAt = if (alwaysOn) 0L else now + totalMs
        running = true
        completed = false
        persistState()

        if (!startForegroundSafely()) return
        if (!showOverlay()) return
        scheduleFinish()
    }

    private fun restoreIfAlive(): Boolean {
        if (!prefs.getBoolean(KEY_RUNNING, false)) return false
        val wasAlways = prefs.getBoolean(KEY_ALWAYS, false)
        val savedEnd = prefs.getLong(KEY_END_AT, 0L)
        if (!wasAlways && savedEnd <= System.currentTimeMillis()) return false
        if (!Settings.canDrawOverlays(this)) return false

        startAt = prefs.getLong(KEY_START_AT, System.currentTimeMillis())
        alwaysOn = wasAlways
        totalMs = prefs.getLong(KEY_TOTAL_MS, 0L)
        endAt = savedEnd
        running = true
        completed = false

        if (!startForegroundSafely()) return false
        if (!showOverlay()) return false
        scheduleFinish()
        return true
    }

    private fun startForegroundSafely(): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildRunningNotification())
            true
        } catch (error: Exception) {
            running = false
            clearPersistedState()
            stopSelf()
            false
        }
    }

    private fun showOverlay(): Boolean {
        if (overlayView != null) return true
        if (!Settings.canDrawOverlays(this)) {
            stopEverything()
            return false
        }

        val view = View(this).apply {
            keepScreenOn = true
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0.05f
            title = "ChangLiangKeepScreenOn"
        }

        return try {
            windowManager.addView(view, params)
            overlayView = view
            true
        } catch (error: Exception) {
            overlayView = null
            stopEverything()
            false
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // 窗口可能已经被系统移除
            }
        }
        overlayView = null
    }

    private fun scheduleFinish() {
        handler.removeCallbacks(finishRunnable)
        if (alwaysOn) return
        val delay = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(finishRunnable, delay)
    }

    private fun finishByTimer() {
        if (!running || alwaysOn) return
        if (System.currentTimeMillis() < endAt) {
            scheduleFinish()
            return
        }

        completed = true
        running = false
        alwaysOn = false
        endAt = 0L
        persistState()
        handler.removeCallbacks(finishRunnable)
        removeOverlay()
        notificationManager.notify(NOTIFICATION_ID, buildCompletedNotification())
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopManually() {
        completed = false
        stopEverything()
    }

    private fun stopEverything() {
        running = false
        alwaysOn = false
        endAt = 0L
        clearPersistedState()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun persistState() {
        prefs.edit()
            .putBoolean(KEY_RUNNING, running)
            .putBoolean(KEY_ALWAYS, alwaysOn)
            .putLong(KEY_END_AT, endAt)
            .putLong(KEY_START_AT, startAt)
            .putLong(KEY_TOTAL_MS, totalMs)
            .apply()
    }

    private fun clearPersistedState() {
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_ALWAYS, false)
            .putLong(KEY_END_AT, 0L)
            .putLong(KEY_START_AT, 0L)
            .putLong(KEY_TOTAL_MS, 0L)
            .apply()
    }

    private fun buildRunningNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KeepOnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gauge)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(
                if (alwaysOn) {
                    getString(R.string.notif_always_text)
                } else {
                    getString(R.string.notif_timed_text, formatClock(endAt))
                }
            )
            .setSubText(getString(R.string.notif_subtext))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(getColor(R.color.notification_accent))
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_stop,
                    getString(R.string.notif_stop),
                    stopIntent
                ).build()
            )

        if (!alwaysOn) {
            builder
                .setWhen(endAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun buildCompletedNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gauge)
            .setContentTitle(getString(R.string.notif_done_title))
            .setContentText(getString(R.string.notif_done_text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(getColor(R.color.notification_accent))
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatClock(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    }
}
