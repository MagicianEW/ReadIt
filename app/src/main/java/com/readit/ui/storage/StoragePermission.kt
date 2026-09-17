package com.readit.ui.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 自定义书籍目录所需的存储权限（分级）。
 *
 * - **API ≤ 29**：`READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE` 运行时权限；API 29 还需
 *   Manifest 里 `requestLegacyExternalStorage=true` 才能用 [java.io.File] 访问外部存储。
 * - **API ≥ 30**：`MANAGE_EXTERNAL_STORAGE`（「所有文件访问」），只能跳系统设置页授予。
 *
 * 说明：之所以要「所有文件访问」而不是 SAF 树，是因为阅读器四条读取链
 * （TXT/EPUB/DOCX/PDF）全走 `File` 绝对路径，改造代价过大（见 ReadItPrefs.booksDir）。
 */
object StoragePermission {

    /**
     * 是否已具备「可读写外部存储」的权限。
     *
     * API ≤ 29 要求 **READ + WRITE 都为已授予**：目录选择器靠 `File.listFiles()`
     * 枚举子目录，缺 READ 时 `listFiles()` 返回 null，界面会显示成「没有子文件夹」——
     * 典型静默失败。两者同属 STORAGE 组，正常弹窗授权时一并到手，故此处不会误报。
     */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    /**
     * 需要运行时申请的权限（API ≤ 29）；API 30+ 返回空数组（改跳设置页）。
     *
     * READ 与 WRITE 都显式申请：用户点系统弹窗时授组即含两者，但 `adb pm grant`、
     * 自动化脚本、以及个别 OEM ROM 只按「权限名」授予，此时少了 READ 会让
     * 目录列表静默变空（KY-01L 真机复现过），所以两个都点名。
     */
    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            emptyArray()
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** API 30+ 的「所有文件访问」授权页；低版本返回 null。 */
    fun allFilesAccessIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
}
