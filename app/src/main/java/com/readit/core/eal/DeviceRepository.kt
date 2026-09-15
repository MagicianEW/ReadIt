package com.readit.core.eal

import android.content.Context
import com.google.gson.Gson
import com.readit.core.util.ReadItLog
import java.io.File
import java.io.IOException

/**
 * 设备配置库：内置 assets/readit_device_profiles.json + 手动导入 JSON。
 * 见规范 §4.2（F18：内置 + 手动导入）。
 */
object DeviceRepository {

    private const val ASSET_NAME = "readit_device_profiles.json"
    private const val IMPORT_NAME = "readit_device_profiles_imported.json"

    private val gson = Gson()

    @Volatile
    private var db: DeviceProfileDb? = null

    fun get(context: Context): DeviceProfileDb {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            val loaded = load(context)
            db = loaded
            return loaded
        }
    }

    private fun load(context: Context): DeviceProfileDb {
        val merged = DeviceProfileDb()
        val devices = LinkedHashMap<String, DeviceProfile>()

        readAsset(context)?.devices?.forEach { devices[key(it)] = it }
        readImported(context)?.devices?.forEach { devices[key(it)] = it }

        merged.devices = devices.values.toList()
        merged.fallbackRules = readAsset(context)?.fallbackRules ?: FallbackRules()
        ReadItLog.i("DeviceRepository loaded: ${merged.devices.size} profiles")
        return merged
    }

    /**
     * 手动导入（外部 JSON），成功后覆盖导入文件并刷新缓存。
     *
     * @return 本次导入的设备条目数（用于回显给用户）
     */
    @Throws(IOException::class)
    fun importProfiles(context: Context, source: File): Int {
        val text = source.readText(Charsets.UTF_8)
        val parsed = gson.fromJson(text, DeviceProfileDb::class.java)
            ?: throw IOException("parse failed: ${source.name}")
        val devices = parsed.devices ?: emptyList()
        if (devices.isEmpty()) throw IOException("no devices in ${source.name}")
        val target = File(context.filesDir, IMPORT_NAME)
        val tmp = File(context.filesDir, "$IMPORT_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("rename failed")
        synchronized(this) { db = null }
        ReadItLog.i("DeviceRepository imported ${devices.size} profiles")
        return devices.size
    }

    private fun readAsset(context: Context): DeviceProfileDb? = try {
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use {
            gson.fromJson(it, DeviceProfileDb::class.java)
        }
    } catch (e: Exception) {
        ReadItLog.e("readAsset $ASSET_NAME failed", e)
        null
    }

    private fun readImported(context: Context): DeviceProfileDb? {
        val f = File(context.filesDir, IMPORT_NAME)
        if (!f.exists()) return null
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), DeviceProfileDb::class.java)
        } catch (e: Exception) {
            ReadItLog.e("readImported failed", e)
            null
        }
    }

    private fun key(p: DeviceProfile) =
        "${p.brand.uppercase()}|${p.model.uppercase()}|${p.device.uppercase()}"
}
