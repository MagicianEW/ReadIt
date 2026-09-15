package com.readit.core.util

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * ReadIt 日志工具：统一 TAG = "ReadIt"（规范 §0）。
 */
object ReadItLog {

    private const val TAG = "ReadIt"
    private const val MAX_LEN = 3500

    fun v(msg: String) = log(Log.VERBOSE, msg)
    fun d(msg: String) = log(Log.DEBUG, msg)
    fun i(msg: String) = log(Log.INFO, msg)
    fun w(msg: String) = log(Log.WARN, msg)
    fun e(msg: String, tr: Throwable? = null) {
        log(Log.ERROR, msg)
        tr?.let { Log.e(TAG, "  stacktrace: ${it.message}", it) }
    }

    private fun log(priority: Int, msg: String) {
        if (msg.length <= MAX_LEN) {
            Log.println(priority, TAG, msg)
            return
        }
        var i = 0
        while (i < msg.length) {
            val end = (i + MAX_LEN).coerceAtMost(msg.length)
            Log.println(priority, TAG, msg.substring(i, end))
            i = end
        }
    }

    fun processName(context: Context): String {
        val pid = android.os.Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.runningAppProcesses?.forEach {
            if (it.pid == pid) return it.processName
        }
        return "unknown"
    }
}
