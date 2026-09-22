package com.readit.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import java.util.concurrent.TimeUnit

/**
 * 定时同步的挂/摘（§2.3，未闭环项 A1）。零依赖：只用平台 `JobScheduler`（API 21+）。
 *
 * ## 两个刻意的设计
 *
 * 1. **只在非计费网络跑**（`NETWORK_TYPE_UNMETERED`）。目标用户是「家里 NAS + 墨水屏」，
 *    但也常出现手机热点场景；静默同步绝不该消耗用户流量。
 *
 * 2. **任务已存在就不重挂**。`JobScheduler.schedule()` 对同一 id 是**替换**语义，
 *    会重置周期计时。若每次 App 冷启动都无脑 apply，用户只要勤开 App
 *    就永远等不到第一次触发 —— 这是最隐蔽的一类「功能没生效」。
 *    所以先查 `allPendingJobs`，在就原样保留（同步参数在运行时从 prefs 现读，不需要重挂）。
 *
 * ## 调用时机
 *  - `ReadItApp.onCreate`（仅主进程）：冷启动时校准一次
 *  - 设置页：自动同步开关变更、WebDAV 参数变更后
 */
object SyncScheduler {

    fun apply(context: Context) {
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            ReadItLog.i("sync schedule: api=${Build.VERSION.SDK_INT} < 21, no JobScheduler, manual only")
            return
        }
        val scheduler = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        if (scheduler == null) {
            ReadItLog.w("sync schedule: JobScheduler unavailable")
            return
        }

        val api = Build.VERSION.SDK_INT
        val prefs = ReadItPrefs.get(ctx)
        val wanted = SyncCapability.shouldSchedule(api, prefs.syncAutoEnabled, prefs.webDavConfigured)
        val existing = runCatching {
            scheduler.allPendingJobs?.any { it.id == SyncCapability.JOB_ID } == true
        }.getOrDefault(false)

        if (!wanted) {
            if (existing) {
                scheduler.cancel(SyncCapability.JOB_ID)
                ReadItLog.i("sync schedule: cancelled (enabled=${prefs.syncAutoEnabled} configured=${prefs.webDavConfigured} api=$api)")
            }
            return
        }
        if (existing) {
            ReadItLog.i("sync schedule: already scheduled, keep timer")
            return
        }

        val hours = SyncCapability.intervalHours(api)
        val intervalMs = TimeUnit.HOURS.toMillis(hours.toLong())
        val builder = JobInfo.Builder(
            SyncCapability.JOB_ID,
            ComponentName(ctx, SyncJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPersisted(true)
            .setBackoffCriteria(
                TimeUnit.MINUTES.toMillis(10),
                JobInfo.BACKOFF_POLICY_EXPONENTIAL
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 1GB 墨水屏机型存储普遍紧张，存不下时先别同步
            builder.setRequiresStorageNotLow(true)
            builder.setPeriodic(intervalMs, SyncCapability.FLEX_MS)
        } else {
            builder.setPeriodic(intervalMs)
        }

        val code = runCatching { scheduler.schedule(builder.build()) }
        ReadItLog.i(
            "sync schedule: api=$api every ${hours}h mode=${SyncCapability.modeOf(api)} -> " +
                (code.getOrNull()?.toString() ?: "failed: ${code.exceptionOrNull()?.message}")
        )
    }
}
