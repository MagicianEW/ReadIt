package com.readit.sync

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import com.readit.sync.webdav.WebDavClient
import java.util.concurrent.Executors

/**
 * 定时同步的入口（§2.3，未闭环项 A1）。由 [SyncScheduler] 用 `JobScheduler` 周期调度。
 *
 * 两条执行路径：
 *  - [SyncCapability.Mode.BACKGROUND]（B 级，API 23-25）：直接在本服务的后台线程跑完，不起通知。
 *  - [SyncCapability.Mode.FOREGROUND]（C/D 级，API 26+）：交给 [SyncForegroundService]，
 *    由它做前台化。
 *
 * 前台服务**被系统拒绝**时的兜底：API 31+ 对「后台启动前台服务」有额外限制，
 * 虽然 JobService 执行期间应用通常处于临时白名单、`startForegroundService` 会放行，
 * 但这属于系统策略、不可假定 —— 所以失败时降级为后台静默执行，
 * **绝不因为起不了通知就整轮同步不跑**。
 */
class SyncJobService : JobService() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "readit-sync-job") }
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        val prefs = ReadItPrefs.get(ctx)

        if (!prefs.webDavConfigured) {
            ReadItLog.w("sync job: webdav not configured, skip")
            return false
        }

        if (SyncCapability.modeOf(Build.VERSION.SDK_INT) == SyncCapability.Mode.FOREGROUND) {
            val started = runCatching {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, SyncForegroundService::class.java)
                )
            }
            if (started.isSuccess) {
                ReadItLog.i("sync job: handed over to foreground service")
                return false
            }
            ReadItLog.w("sync job: fg service refused -> ${started.exceptionOrNull()?.message}, run inline")
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
            ReadItLog.i("sync job finished: " + describe(outcome))
            main.post { jobFinished(params, false) }
        }
        return true
    }

    /** 被系统打断（多半是网络切换）→ 要求重排，下一轮继续 */
    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun describe(outcome: Result<SyncRunner.Outcome>): String =
        outcome.getOrNull()?.summary() ?: "failed: ${outcome.exceptionOrNull()?.message}"
}
