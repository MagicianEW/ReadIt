package com.readit.ui.onboarding

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.readit.core.eal.Eal
import com.readit.core.eal.PerfTier
import com.readit.core.eal.RefreshMode
import com.readit.core.eal.RefreshModeManager
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import com.readit.data.storage.StorageManager
import com.readit.eink.R
import com.readit.ui.input.KeyLearnDialog
import java.io.File

/**
 * 首次启动引导（§8 Phase 4「引导 + 设置 + 备份」）。
 *
 * 设计取舍：**不做轮播 / 不做动画**。E-Ink 上任何过渡都会留下残影，
 * 而引导页只出现一次，用最朴素的「4 个容器切 visibility」实现最稳。
 *
 * 每一步都是「能立刻生效的设置」而不是介绍页 —— 用户点完就已经配好了，
 * 而不是看 4 屏说明再自己去设置里找。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var prefs: ReadItPrefs
    private var step = OnboardingStep.TIER

    /** 回填控件时抑制监听器，避免「初始化」被当成「用户改动」触发一轮 EAL reload */
    private var binding = false

    private lateinit var tvStep: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvDesc: TextView
    private lateinit var grpTier: View
    private lateinit var grpRefresh: View
    private lateinit var grpInput: View
    private lateinit var grpImport: View
    private lateinit var rgTier: RadioGroup
    private lateinit var rgRefresh: RadioGroup
    private lateinit var tvTierDetected: TextView
    private lateinit var tvLearnedKeys: TextView
    private lateinit var tvImported: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button

    private val pickBook =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBook(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        prefs = ReadItPrefs.get(this)
        step = OnboardingStep.of(savedInstanceState?.getInt(KEY_STEP) ?: 0)

        tvStep = findViewById(R.id.tvOnboardStep)
        tvTitle = findViewById(R.id.tvOnboardTitle)
        tvDesc = findViewById(R.id.tvOnboardDesc)
        grpTier = findViewById(R.id.grpTier)
        grpRefresh = findViewById(R.id.grpRefresh)
        grpInput = findViewById(R.id.grpInput)
        grpImport = findViewById(R.id.grpImport)
        rgTier = findViewById(R.id.rgTier)
        rgRefresh = findViewById(R.id.rgRefresh)
        tvTierDetected = findViewById(R.id.tvTierDetected)
        tvLearnedKeys = findViewById(R.id.tvLearnedKeys)
        tvImported = findViewById(R.id.tvImported)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)

        bindTier()
        bindRefresh()

        findViewById<Button>(R.id.btnLearnKey).setOnClickListener {
            KeyLearnDialog.show(this)
        }
        findViewById<Button>(R.id.btnPickBook).setOnClickListener {
            pickBook.launch(arrayOf("*/*"))
        }
        btnPrev.setOnClickListener {
            step.prev()?.let { go(it) }
        }
        btnNext.setOnClickListener {
            step.next()?.let { go(it) } ?: finishOnboarding()
        }
        findViewById<Button>(R.id.btnSkip).setOnClickListener { finishOnboarding() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // 按键学习向导是独立对话框，回来时刷新一下已学列表
        renderLearnedKeys()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step.id)
    }

    /**
     * 返回键：首步直接等价于「跳过」。
     *
     * 不做成「什么都不写、下次再问」—— 那会让连续按返回的用户每次启动都被拦一次，
     * 而引导页本身没有任何破坏性，没必要这么固执。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val prev = step.prev()
        if (prev != null) go(prev) else finishOnboarding()
    }

    // ------------------------------------------------------------------ 步骤

    private fun go(target: OnboardingStep) {
        step = target
        render()
    }

    private fun finishOnboarding() {
        prefs.onboardingDone = true
        ReadItLog.i("onboarding finished at step=${step.id}")
        finish()
    }

    private fun render() {
        tvStep.text = getString(R.string.onboard_step, step.humanIndex, OnboardingStep.TOTAL)
        when (step) {
            OnboardingStep.TIER -> {
                tvTitle.setText(R.string.onboard_title_tier)
                tvDesc.setText(R.string.onboard_desc_tier)
            }
            OnboardingStep.REFRESH -> {
                tvTitle.setText(R.string.onboard_title_refresh)
                tvDesc.setText(R.string.onboard_desc_refresh)
            }
            OnboardingStep.INPUT -> {
                tvTitle.setText(R.string.onboard_title_input)
                tvDesc.setText(R.string.onboard_desc_input)
            }
            OnboardingStep.IMPORT -> {
                tvTitle.setText(R.string.onboard_title_import)
                tvDesc.setText(R.string.onboard_desc_import)
            }
        }
        grpTier.visibility = if (step == OnboardingStep.TIER) View.VISIBLE else View.GONE
        grpRefresh.visibility = if (step == OnboardingStep.REFRESH) View.VISIBLE else View.GONE
        grpInput.visibility = if (step == OnboardingStep.INPUT) View.VISIBLE else View.GONE
        grpImport.visibility = if (step == OnboardingStep.IMPORT) View.VISIBLE else View.GONE

        btnPrev.isEnabled = !step.isFirst
        btnNext.setText(if (step.isLast) R.string.onboard_finish else R.string.onboard_next)

        bindTierSummary()
        renderLearnedKeys()
    }

    // ------------------------------------------------------------------ 步骤 1：档位

    private fun bindTier() {
        binding = true
        rgTier.check(
            when (prefs.perfTier) {
                PerfTier.FALLBACK -> R.id.rbTierFallback
                PerfTier.STANDARD -> R.id.rbTierStandard
                PerfTier.ENHANCED -> R.id.rbTierEnhanced
                PerfTier.AUTO -> R.id.rbTierAuto
            }
        )
        binding = false

        rgTier.setOnCheckedChangeListener { _, checkedId ->
            if (binding) return@setOnCheckedChangeListener
            prefs.perfTier = when (checkedId) {
                R.id.rbTierFallback -> PerfTier.FALLBACK
                R.id.rbTierStandard -> PerfTier.STANDARD
                R.id.rbTierEnhanced -> PerfTier.ENHANCED
                else -> PerfTier.AUTO
            }
            // 档位是 EAL 的输入而 Eal 有进程内缓存；不 reload 就要重启才生效
            Eal.reload(this)
            bindTierSummary()
        }
    }

    private fun bindTierSummary() {
        val r = Eal.get(this)
        tvTierDetected.text = getString(
            R.string.onboard_tier_detected,
            r.socClass.key,
            r.ramClass.key,
            r.totalRamMb,
            if (r.isKnownDevice) getString(R.string.onboard_known_yes)
            else getString(R.string.onboard_known_no)
        )
    }

    // ------------------------------------------------------------------ 步骤 2：刷新

    private fun bindRefresh() {
        binding = true
        rgRefresh.check(
            when (prefs.refreshMode) {
                RefreshMode.QUALITY -> R.id.rbRefreshQuality
                RefreshMode.FAST -> R.id.rbRefreshFast
                RefreshMode.REGAL -> R.id.rbRefreshRegal
                RefreshMode.SYSTEM -> R.id.rbRefreshSystem
                RefreshMode.AUTO -> R.id.rbRefreshAuto
            }
        )
        binding = false

        rgRefresh.setOnCheckedChangeListener { _, checkedId ->
            if (binding) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbRefreshQuality -> RefreshMode.QUALITY
                R.id.rbRefreshFast -> RefreshMode.FAST
                R.id.rbRefreshRegal -> RefreshMode.REGAL
                R.id.rbRefreshSystem -> RefreshMode.SYSTEM
                else -> RefreshMode.AUTO
            }
            prefs.refreshMode = mode
            RefreshModeManager.apply(mode)
        }
    }

    // ------------------------------------------------------------------ 步骤 3：按键

    private fun renderLearnedKeys() {
        if (!::tvLearnedKeys.isInitialized) return
        val maps = prefs.allKeyMaps()
        tvLearnedKeys.text = if (maps.isEmpty()) {
            getString(R.string.onboard_learned_none)
        } else {
            getString(
                R.string.onboard_learned_keys,
                maps.entries.joinToString("、") { "${it.key}→${it.value}" }
            )
        }
    }

    // ------------------------------------------------------------------ 步骤 4：导入

    private fun importBook(uri: Uri) {
        try {
            val tmp = File(StorageManager.tmpDir(this), "onboard_${System.currentTimeMillis()}.part")
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw java.io.IOException("cannot open uri")
            val name = queryName(uri) ?: tmp.name
            StorageManager.importAtomic(this, tmp, name, tmp.length())
            tmp.delete()
            tvImported.text = getString(R.string.onboard_imported, name)
        } catch (e: Exception) {
            ReadItLog.e("onboarding import failed", e)
            Toast.makeText(this, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val KEY_STEP = "onboard_step"
    }
}
