package com.readit.ui.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.readit.core.util.ReadItLog
import com.readit.diag.DeviceReport
import com.readit.diag.DeviceReportData
import com.readit.diag.KeyProbe
import com.readit.eink.R

/**
 * 设备报告与按键采集（§8 Phase 4「设备收集工具」）。
 *
 * 解决的是 EAL 最现实的问题：**未知设备只能走 fallback**，而要让某台机器进设备库，
 * 维护者手上得有它的 SoC 线索、内存档、分辨率、WebView 版本和真实按键码。
 * 这个页面把这些一次收齐，并直接产出**能贴进设备库的 JSON**，
 * 而不是让用户去论坛描述「我这台打开有点慢」。
 */
class DeviceCollectActivity : AppCompatActivity() {

    private enum class ExportKind { REPORT, PROFILE }

    private val probe = KeyProbe()
    private var probing = false
    private var pendingExport: ExportKind? = null

    private lateinit var tvDeviceText: TextView
    private lateinit var tvKeySamples: TextView
    private lateinit var btnProbe: Button

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val kind = pendingExport
            pendingExport = null
            if (uri == null || kind == null) return@registerForActivityResult
            writeExport(uri, kind)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_collect)

        tvDeviceText = findViewById(R.id.tvDeviceText)
        tvKeySamples = findViewById(R.id.tvKeySamples)
        btnProbe = findViewById(R.id.btnProbe)

        btnProbe.setOnClickListener {
            probing = !probing
            renderProbeButton()
            Toast.makeText(
                this,
                if (probing) R.string.diag_key_started else R.string.diag_key_stopped,
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<Button>(R.id.btnProbeClear).setOnClickListener {
            probe.clear()
            renderKeySamples()
        }
        findViewById<Button>(R.id.btnExportReport).setOnClickListener {
            pendingExport = ExportKind.REPORT
            createDoc.launch("readit_device_report.json")
        }
        findViewById<Button>(R.id.btnExportProfile).setOnClickListener {
            pendingExport = ExportKind.PROFILE
            createDoc.launch("readit_device_profile.json")
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyToClipboard() }

        render()
    }

    /**
     * 采集期间吞掉按键，避免上下键把页面滚走。
     * **BACK 不吞** —— 否则用户会被困在这一页。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (!probing) {
            // 未在采集时不拦截，保持页面正常滚动
            return super.onKeyDown(keyCode, event)
        }
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val isNew = probe.record(
            keyCode = event.keyCode,
            name = name,
            scanCode = event.scanCode,
            source = sourceName(event.source),
            isDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        )
        if (isNew) {
            ReadItLog.i("key probe: $name keyCode=${event.keyCode} scan=${event.scanCode}")
            renderKeySamples()
        }
        return true
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        // collect() 里有 WebViewCapability.probe，极端情况下要构造 WebView → 放主线程
        val data = DeviceReport.collect(this, probe.samples())
        tvDeviceText.text = DeviceReport.toText(data)
        renderProbeButton()
        renderKeySamples()
    }

    private fun renderProbeButton() {
        btnProbe.setText(if (probing) R.string.diag_key_stop else R.string.diag_key_start)
    }

    private fun renderKeySamples() {
        val list = probe.samples()
        tvKeySamples.text = if (list.isEmpty()) {
            getString(R.string.diag_key_empty)
        } else {
            list.joinToString("\n") {
                "${it.name}  keyCode=${it.keyCode}  scan=${it.scanCode}  ${it.source}  x${it.count}"
            }
        }
    }

    private fun currentData(): DeviceReportData = DeviceReport.collect(this, probe.samples())

    // ------------------------------------------------------------------ 导出

    private fun writeExport(uri: android.net.Uri, kind: ExportKind) {
        try {
            val json = when (kind) {
                ExportKind.REPORT -> DeviceReport.toJson(currentData())
                ExportKind.PROFILE -> DeviceReport.toProfileJson(currentData())
            }
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw java.io.IOException("无法写入所选位置")
            Toast.makeText(this, getString(R.string.diag_export_ok), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            ReadItLog.e("device report export failed", e)
            Toast.makeText(this, getString(R.string.diag_export_failed, e.message ?: ""), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("ReadIt device report", DeviceReport.toText(currentData())))
        Toast.makeText(this, getString(R.string.diag_copied), Toast.LENGTH_SHORT).show()
    }

    /** 用真实 InputDevice 常量而不是手搓掩码 —— 掩码写错会静默把遥控器归成「其他」 */
    private fun sourceName(source: Int): String = when {
        source == InputDevice.SOURCE_GAMEPAD -> "GAMEPAD"
        source == InputDevice.SOURCE_DPAD -> "DPAD"
        source == InputDevice.SOURCE_TRACKBALL -> "TRACKBALL"
        (source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD -> "KEYBOARD"
        else -> "OTHER"
    }
}
