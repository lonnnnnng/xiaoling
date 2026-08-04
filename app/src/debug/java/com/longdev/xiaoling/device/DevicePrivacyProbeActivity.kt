package com.longdev.xiaoling.device

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class DevicePrivacyProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            setIntent(it)
            render(it)
        }
    }

    private fun render(intent: android.content.Intent) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(48, 96, 48, 48)
        }
        if (intent.getStringExtra(EXTRA_MODE) == MODE_TYPE_TEXT) {
            layout.isFocusableInTouchMode = true
            layout.requestFocus()
            layout.addView(TextView(this).apply { text = "Workflow 文本输入验收" })
            layout.addView(
                EditText(this).apply {
                    // long: type_text 使用独立 Probe，不改动已验收的点击/隐私页结构；固定非敏感占位文本避免空 EditText 在部分 ROM 中来回切换 text/hint 可见形态。
                    hint = "Workflow 安全文本输入框"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                    isSingleLine = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setText("ready")
                },
            )
        } else {
            layout.addView(TextView(this).apply { text = "设备动作验收" })
            val actionStatus = TextView(this).apply { text = "等待测试动作" }
            layout.addView(actionStatus)
            layout.addView(
                Button(this).apply {
                    text = "测试按钮"
                    // long: 真机 tracer bullet 需要可复核的页面变化；明确更新状态文本，避免仅凭 ACTION_CLICK 返回 true 就把无业务效果的点击判为成功。
                    setOnClickListener { actionStatus.text = "动作已完成" }
                },
            )
        }
        setContentView(layout)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_TYPE_TEXT = "type_text"
    }
}
