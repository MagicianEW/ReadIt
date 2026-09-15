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
            if (f.exists() && !f.delete()) ReadItLog.w("sync state delete failed")
            if (!tmp.renameTo(f)) {
                // rename 失败（少见，跨卷/被占用）时退化直写，保证记录不丢
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
