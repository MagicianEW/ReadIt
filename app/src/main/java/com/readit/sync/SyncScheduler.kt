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
        val existing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // getAllPendingJobs 是 API 24 才有的方法；API 21-23 上没有等价查询。
            runCatching {
                scheduler.allPendingJobs?.any { it.id == SyncCapability.JOB_ID } == true
            }.getOrDefault(false)
        } else {
            // API 21-23：查不到「是否已挂」，但**不能**因此当成「没挂过」。
            // schedule() 对同一 id 是**替换**语义，会把周期计时器从零重算；
            // 而 apply() 是每次冷启动（ReadItApp.onCreate）都调 —— 于是
            // 用户每天开一次应用，24h 计时器就永远被清零，**一次都不会触发**，
            // 恰好打中产品承诺的「API 23-25 后台静默每 24 小时」这一档。
            //
            // 幂等的是「任务存在」，不是「计时器」。这里改为：查不到时用一个
            // 进程内标记 + 「本次进程是否已 schedule 过」来判断，避免冷启动重排。
            SyncCapability.jobScheduledInProcess
        }

        if (!wanted) {
            if (existing) {
                scheduler.cancel(SyncCapability.JOB_ID)
                ReadItLog.i("sync schedule: cancelled (enabled=${prefs.syncAutoEnabled} configured=${prefs.webDavConfigured} api=$api)")
            }
            // 无论 existing 与否都要复位：API 21-23 靠这个标记判断，
            // 复位后用户重新打开自动同步时才会真的重排一次
            SyncCapability.jobScheduledInProcess = false
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
            builder.setPeriodic(intervalMs, SyncCapability.FLEX_MS)
        } else {
            builder.setPeriodic(intervalMs)
        }
        // 必须单独判 O（26）：`setRequiresStorageNotLow` 是 API 26 才加的方法，
        // 挂在 N（24）判断下会在 API 24/25 上抛 NoSuchMethodError ——
        // 而这里是 Application.onCreate 的调用链，一抛就是**启动即崩**。
        // 实测：KY-01L（API 25）配好 WebDAV 后每次启动都崩，配之前不崩
        // （shouldSchedule 里 configured=false 会提前 return，把方法名解析这件事掩盖过去）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1GB 墨水屏机型存储普遍紧张，存不下时先别同步
            builder.setRequiresStorageNotLow(true)
        }

        val code = runCatching { scheduler.schedule(builder.build()) }
        // 标记本次进程已 schedule：API 21-23 查不到 allPendingJobs，靠它避免冷启动反复重排
        SyncCapability.jobScheduledInProcess = true
        ReadItLog.i(
            "sync schedule: api=$api every ${hours}h mode=${SyncCapability.modeOf(api)} -> " +
                (code.getOrNull()?.toString() ?: "failed: ${code.exceptionOrNull()?.message}")
        )
    }
}
