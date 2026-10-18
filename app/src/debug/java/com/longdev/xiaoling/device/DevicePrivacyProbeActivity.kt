package com.longdev.xiaoling.device

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.view.accessibility.AccessibilityNodeInfo

class DevicePrivacyProbeActivity : Activity() {
    private var publicClickCount = 0
    private var publicMutationTrace = "none"
    override fun onResume() {
        super.onResume()
        resumedProbe = this
    }

    override fun onPause() {
        if (resumedProbe === this) resumedProbe = null
        super.onPause()
    }
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
        publicClickCount = 0
        publicMutationTrace = "none"
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (intent.getStringExtra(EXTRA_MODE) == MODE_HORIZONTAL_SCROLL) {
            renderPublicHorizontalScroll()
            return
        }
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
        } else if (intent.getStringExtra(EXTRA_MODE) == MODE_SCROLL) {
            // long: 滚动验收只使用宿主公开固定内容，避免为验证 viewport 变化读取用户的系统设置或其他应用资料。
            layout.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            repeat(60) { index -> layout.addView(TextView(this).apply {
                text = "公开滚动验收条目 ${index + 1}"
                setPadding(0, 32, 0, 32)
            }) }
        } else {
            layout.addView(TextView(this).apply { text = "设备动作验收" })
            val actionStatus = TextView(this).apply { text = "等待测试动作" }
            // long: 公开按钮状态在审批前漂移时，记录类与方法来源，区分本轮节点动作和页面之外的点击；不记录任何参数或用户窗口。
            actionStatus.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (s.toString() == "动作已完成") publicMutationTrace = Throwable().stackTrace.take(30)
                        .joinToString("|") { "${it.className}#${it.methodName}" }
                }
            })
            layout.addView(actionStatus)
            layout.addView(
                Button(this).apply {
                    text = "测试按钮"
                    // long: 真机 tracer bullet 需要可复核的页面变化；明确更新状态文本，避免仅凭 ACTION_CLICK 返回 true 就把无业务效果的点击判为成功。
                    setOnClickListener { publicClickCount++; actionStatus.text = "动作已完成" }
                },
            )
        }
        if (intent.getStringExtra(EXTRA_MODE) == MODE_SCROLL) setContentView(object : ScrollView(this) {
            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                    action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    // long: 保留半屏共同锚点才能独立核实位移方向；原生整屏动作会移走全部锚点，本页仍实际滚动且返回真实位置变化。
                    val before = scrollY
                    val step = maxOf(1, height / 2)
                    scrollBy(0, if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) step else -step)
                    return scrollY != before
                }
                return super.performAccessibilityAction(action, arguments)
            }
        }.apply { addView(layout) })
        else setContentView(layout)
    }

    private fun renderPublicHorizontalScroll() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            repeat(30) { index -> addView(TextView(context).apply {
                text = "公开横向条目 ${index + 1}"
                layoutParams = LinearLayout.LayoutParams(180, ViewGroup.LayoutParams.MATCH_PARENT)
                gravity = android.view.Gravity.CENTER
            }) }
        }
        setContentView(object : HorizontalScrollView(this) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                // long: 明确声明真实能移动的左右动作，边界不伪造支持；原生视图实际位置和生产 Executor 的方向判断必须同时通过。
                info.isScrollable = true
                if (scrollX > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
                if (scrollX + width < getChildAt(0).width) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
            }

            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id ||
                    action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id) {
                    // long: 半屏位移保留共同公开锚点供方向验证；仍由真实 View 滚动并以 scrollX 变化返回，不构造伪窗口或结果。
                    val before = scrollX
                    scrollBy(if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id) -width / 2 else width / 2, 0)
                    return scrollX != before
                }
                return super.performAccessibilityAction(action, arguments)
            }
        }.apply {
            addView(row)
            // long: 起点位于公开列表中段，让 left 和 right 各自拥有可移动空间；起始定位属于页面夹具，不计作模型动作成功。
            post { scrollTo(width, 0) }
        })
    }

    fun publicClickAudit(nonce: String): String {
        check(intent.getStringExtra(EXTRA_LIVE_NONCE) == nonce)
        return "clicks=$publicClickCount mutationTrace=$publicMutationTrace"
    }

    companion object {
        @Volatile private var resumedProbe: DevicePrivacyProbeActivity? = null
        const val EXTRA_LIVE_NONCE = "public_live_nonce"
        // long: 包名不能区分公开 Probe 与正式会话页；nonce 与真实 resumed/focus 联合绑定，防止测试误采集同包用户页面。
        fun currentResumedPublicProbe(nonce: String): DevicePrivacyProbeActivity? = resumedProbe?.takeIf {
            it.hasWindowFocus() && it.intent.getStringExtra(EXTRA_LIVE_NONCE) == nonce
        }
        fun ownsResumedPublicProbe(nonce: String): Boolean = currentResumedPublicProbe(nonce) != null
        const val EXTRA_MODE = "mode"
        const val MODE_TYPE_TEXT = "type_text"
        const val MODE_SCROLL = "scroll"
        const val MODE_HORIZONTAL_SCROLL = "horizontal_scroll"
    }
}
