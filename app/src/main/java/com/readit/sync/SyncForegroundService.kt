package com.readit.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import com.readit.eink.R
import com.readit.sync.webdav.WebDavClient
import com.readit.ui.shelf.ShelfActivity
import java.util.concurrent.Executors

/**
 * C/D 级「同步前台化」容器（§2.3，未闭环项 A1）。
 *
 * 为什么需要它：API 26 起后台执行受限，`JobService` 在后台随时可能被系统掐掉；
 * 长一点的同步（一本书几百 MB 的 E-Ink 书库并不罕见）一旦被掐就会留下半截状态。
 * 转前台服务后进程被提到前台优先级，并给用户一个可见、可取消的入口。
 *
 * 只在 [SyncCapability.Mode.FOREGROUND] 档由 [SyncJobService] 拉起；
 * 同步完成后立即 `stopForeground` + `stopSelf`，不做常驻。
 *
 * E-Ink 取舍：渠道 `IMPORTANCE_LOW`、`setOnlyAlertOnce(true)`、不响铃不震动 ——
 * 墨水屏上任何闪烁都是噪音。
 */
class SyncForegroundService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "readit-sync-fg") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ctx = applicationContext
        val prefs = ReadItPrefs.get(ctx)

        // 配置被清空的竞态：任务已经排上但用户又删了地址 —— 直接退出，不要起通知
        if (!prefs.webDavConfigured) {
            ReadItLog.w("sync fg: webdav not configured, abort")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureChannel(ctx)
        val notification = buildNotification(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 34 起必须显式声明类型，缺了直接抛 IllegalArgumentException
            startForeground(
                SyncCapability.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(SyncCapability.NOTIFICATION_ID, notification)
        }

        worker.execute {
            val outcome = runCatching {
                val client = WebDavClient(
                    prefs.webDavUrl,
                    prefs.webDavUser,
                    prefs.webDavPassword,
                    prefs.webDavDir
                )
                SyncRunner.run(ctx, client)
            }
            ReadItLog.i("sync fg finished: " + describe(outcome))
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 内部

    private fun describe(outcome: Result<SyncRunner.Outcome>): String =
        outcome.getOrNull()?.summary() ?: "failed: ${outcome.exceptionOrNull()?.message}"

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(ctx: Context): Notification {
        val tap = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, ShelfActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, SyncCapability.CHANNEL_ID)
            .setSmallIcon(R.drawable.readit_ic_sync)
            .setContentTitle(ctx.getString(R.string.sync_notif_title))
            .setContentText(ctx.getString(R.string.sync_notif_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (mgr.getNotificationChannel(SyncCapability.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            SyncCapability.CHANNEL_ID,
            ctx.getString(R.string.sync_notif_channel),
            // LOW：不响铃、不震动、不弹横幅。E-Ink 上任何打扰都是负收益
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        mgr.createNotificationChannel(channel)
    }
}
