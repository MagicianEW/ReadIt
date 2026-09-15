package com.readit.ui.input

import android.app.Activity
import android.app.Dialog
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.readit.core.input.InputMapper
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import com.readit.eink.R

/**
 * 按键学习向导（规范 §4.4，F19）。
 *
 * 提示用户按下要映射的按键 -> 尝试拦截 KeyCode -> 写入 readit_prefs。
 * 若该按键被系统消费（无法拦截 / 不在白名单），提示「无法学习此按键」。
 */
object KeyLearnDialog {

    fun show(activity: Activity) {
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.setContentView(R.layout.dialog_key_learn)
        dialog.setCancelable(true)

        val tvPrompt = dialog.findViewById<TextView>(R.id.tvKeyLearnPrompt)
        val group = dialog.findViewById<RadioGroup>(R.id.rgActions)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancel)
        val prefs = ReadItPrefs.get(activity)

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val action = when (group.checkedRadioButtonId) {
                R.id.rbPrev -> InputMapper.Action.PREV_PAGE
                R.id.rbNext -> InputMapper.Action.NEXT_PAGE
                R.id.rbMenu -> InputMapper.Action.MENU
                R.id.rbBack -> InputMapper.Action.BACK
                else -> InputMapper.Action.NEXT_PAGE
            }
            val ok = InputMapper.learn(keyCode, action) { k, a -> prefs.putKeyMap(k, a) }
            if (ok) {
                tvPrompt.text = activity.getString(R.string.key_learn_ok, keyCode, action.name)
                ReadItLog.i("key learned: $keyCode -> $action")
                Toast.makeText(
                    activity,
                    activity.getString(R.string.key_learn_ok, keyCode, action.name),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            } else {
                tvPrompt.text = activity.getString(R.string.key_learn_failed)
                Toast.makeText(activity, R.string.key_learn_failed, Toast.LENGTH_SHORT).show()
            }
            true
        }

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
