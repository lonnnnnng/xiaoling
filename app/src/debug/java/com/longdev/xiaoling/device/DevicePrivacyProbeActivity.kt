package com.longdev.xiaoling.device

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(48, 96, 48, 48)
        }
        if (intent.getStringExtra(EXTRA_MODE) == MODE_PAYMENT) {
            layout.addView(TextView(this).apply { text = "请输入支付密码" })
        } else {
            layout.addView(TextView(this).apply { text = "sk-stage2-private-abcdefghijklmnop" })
            layout.addView(
                EditText(this).apply {
                    hint = "登录密码"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText("private-password-value")
                },
            )
            layout.addView(Button(this).apply { text = "安全按钮" })
        }
        setContentView(layout)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PAYMENT = "payment"
    }
}
