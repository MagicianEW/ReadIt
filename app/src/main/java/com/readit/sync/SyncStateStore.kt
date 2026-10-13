package com.readit.sync

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.readit.core.util.ReadItLog
import java.io.File

/**
 * 同步基线记录的本地持久化（F13）。
 *
 * 存 `filesDir/sync_state.json`：{ 文件名 → [SyncRecord] }。
 * 之所以不用 SharedPreferences：条目数随书库增长，且需要原子替换（临时文件 + rename）。
 */
object SyncStateStore {

    private const val FILE_NAME = "sync_state.json"
    private val gson = Gson()
    private val type = object : TypeToken<HashMap<String, SyncRecord>>() {}.type

    @Volatile
    private var cache: HashMap<String, SyncRecord>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context): HashMap<String, SyncRecord> {
        cache?.let { return HashMap(it) }
        val f = file(context)
        val map = if (!f.exists()) {
            HashMap()
        } else {
            try {
                gson.fromJson<HashMap<String, SyncRecord>>(f.readText(Charsets.UTF_8), type)
                    ?: HashMap()
            } catch (e: Exception) {
                ReadItLog.w("sync state load failed: ${e.message}")
                HashMap()
            }
        }
        cache = map
        return HashMap(map)
    }

    @Synchronized
    fun get(context: Context, name: String): SyncRecord? = load(context)[name]

    @Synchronized
    fun put(context: Context, name: String, record: SyncRecord) {
        val map = load(context)
        map[name] = record
        persist(context, map)
    }

    @Synchronized
    fun remove(context: Context, name: String) {
        val map = load(context)
        map.remove(name)
        persist(context, map)
    }

    private fun persist(context: Context, map: HashMap<String, SyncRecord>) {
        cache = map
        val f = file(context)
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(map), Charsets.UTF_8)
            // **先 rename 再删旧的**，不先删。原实现是先 delete 再 rename：
            // delete 成功、rename 失败时只好退化直写，而直写掉一半掉电就留下截断的
            // sync_state.json —— 下次启动解析失败、load 返回空 map，于是**所有书都变成
            // 「无基线」→ 全部判冲突**，同步功能整体瘫掉且无自愈，得手动「重置同步状态」。
            // 同一卷上的 rename 覆盖本来就是原子的，先删没有任何收益。
            if (!tmp.renameTo(f)) {
                ReadItLog.w("sync state rename failed, fallback to direct write")
                f.writeText(gson.toJson(map), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            ReadItLog.w("sync state persist failed: ${e.message}")
        }
    }

    /** 清空（调试 / 重置同步用） */
    @Synchronized
    fun clear(context: Context) {
        cache = HashMap()
        runCatching { file(context).delete() }
    }
}
