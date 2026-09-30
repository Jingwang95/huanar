package com.huanar.le

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 一个常驻前台服务。
 *
 * 为什么需要它：OPPO/一加/realme 等国产系统会主动回收后台进程，
 * 一旦 App 进程被杀，通知监听就会断掉、付完款收不到弹窗。
 * 挂一个前台服务能显著降低被杀的概率（配合设置里的自启动+电池白名单）。
 *
 * 注意它不读取任何数据，只是"占位保活"。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CH_ID = "huanar_alive"
        private const val NOTI_ID = 1001

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Exception) {
                // 后台启动前台服务受限时忽略，不影响主流程
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n: Notification = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("花哪儿了 正在盯着付款通知")
            .setContentText("付完款会弹出分类小窗")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()

        try {
            startForeground(NOTI_ID, n)
        } catch (e: Exception) {
            // 极少数机型/系统版本会对前台服务类型挑刺，此时不影响通知监听本身
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_ID) == null) {
                val ch = NotificationChannel(
                    CH_ID, "运行状态", NotificationManager.IMPORTANCE_MIN
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 被系统杀掉后尽量重启
        try { start(this) } catch (e: Exception) { }
    }
}
