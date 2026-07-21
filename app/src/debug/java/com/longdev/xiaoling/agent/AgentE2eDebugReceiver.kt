package com.longdev.xiaoling.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentE2eDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_E2E) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                when (intent.getStringExtra(EXTRA_OPERATION)) {
                    OPERATION_SETUP -> setup(appContext, intent)
                    OPERATION_STATUS -> reportStatus(appContext)
                    else -> Log.w(TAG, "agent-e2e success=false reason=unknown-operation")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "agent-e2e success=false reason=${error::class.java.simpleName} message=${error.message}")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun setup(context: Context, intent: Intent) {
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL).orEmpty()
        val allowedTools = intent.getStringExtra(EXTRA_ALLOWED_TOOL)
            .orEmpty()
            .ifBlank { DEFAULT_ALLOWED_TOOL }
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        require(allowedTools.isNotEmpty()) { "Debug Agent 至少需要一个允许工具" }
        require(baseUrl.isNotBlank() && model.isNotBlank()) { "mock Provider 配置不完整" }
        val provider = ProviderProfile(
            id = PROVIDER_ID,
            name = "设备动作 E2E",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            availableModels = listOf(model),
            enabledModels = listOf(model),
        )
        ProviderRepository(context).save(listOf(provider), provider.id)
        val now = System.currentTimeMillis()
        val agentProfile = AgentProfileRecord(
            id = AGENT_PROFILE_ID,
            name = "设备打开应用 E2E",
            avatar = "D",
            providerId = provider.id,
            model = model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "仅执行用户明确要求且当前 Profile 已授权的工具。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            // long: 后台 Workflow 会按运行来源移除设备动作；Debug 验收入口允许显式选择受控工具集，才能分别覆盖前台设备动作与后台多步骤调度链路。
            allowedToolNames = allowedTools,
            allowedSkillIds = emptyList(),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        RoomAgentProfileStore(context).apply {
            upsert(agentProfile)
            check(select(agentProfile.id)) { "无法选择 E2E Agent Profile" }
        }
        UiPreferenceStore(context).saveDeviceAgentEnabled(true)
        Log.i(TAG, "agent-e2e setup=true model=$model tools=${allowedTools.joinToString()}")
    }

    private suspend fun reportStatus(context: Context) {
        val detail = RoomAgentRunRepository(context).recentRunDetails(1).firstOrNull()
        if (detail == null) {
            Log.i(TAG, "agent-e2e status=NO_RUN")
            return
        }
        val result = detail.toolLedger.results.lastOrNull()
        val approval = detail.approvals.lastOrNull()
        Log.i(
            TAG,
            "agent-e2e run=${detail.snapshot.run.id} status=${detail.snapshot.run.status} " +
                "approval=${approval?.status} tool=${result?.toolName} success=${result?.success} " +
                "executorVerified=${result?.executorVerified} verification=${result?.verificationStatus}",
        )
    }

    companion object {
        const val ACTION_E2E = "com.longdev.xiaoling.debug.AGENT_E2E"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MODEL = "model"
        const val EXTRA_ALLOWED_TOOL = "allowed_tool"
        const val OPERATION_SETUP = "setup"
        const val OPERATION_STATUS = "status"
        private const val DEFAULT_ALLOWED_TOOL = "device.open_app"
        private const val PROVIDER_ID = "stage3-device-e2e-provider"
        private const val AGENT_PROFILE_ID = "stage3-device-e2e-profile"
        private const val TAG = "XiaoLingAgentE2e"
    }
}
