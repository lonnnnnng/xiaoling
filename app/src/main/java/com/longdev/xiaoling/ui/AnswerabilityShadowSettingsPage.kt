package com.longdev.xiaoling.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityJudgeFailureKind
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowSampleSummary
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowPersistentSummary

@Composable
internal fun AnswerabilityShadowSettingsContent(
    enabled: Boolean,
    sampleSummary: KnowledgeAnswerabilityShadowSampleSummary = KnowledgeAnswerabilityShadowSampleSummary(),
    persistentSummary: KnowledgeAnswerabilityShadowPersistentSummary = KnowledgeAnswerabilityShadowPersistentSummary(),
    onEnabledChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回设置",
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "答案可回答性 Shadow",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "授权下一次答案可回答性 Shadow",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "仅授权下一次符合条件的前台直接 /agent 观测；答案先展示并保存，开始观测时自动关闭本开关。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                        modifier = Modifier.semantics { contentDescription = "授权下一次答案可回答性 Shadow" },
                    )
                }
                // long: Shadow 只增加消息旁路提示；Judge 延迟、失败或否决都不会删除引用、改写答案或开启生产 enforcement。
                Text(
                    "当前边界：默认关闭 · 每次显式开启最多启动一轮观测 · 仅匹配冻结 gpt-5.5 身份时请求 · 不进入普通聊天、Workflow 或后台任务 · 只写入匿名 Room 观测账本。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "跨进程匿名摘要",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "观测 ${persistentSummary.observationCount} · Judge 身份 ${persistentSummary.judgeIdentityCount} · 完成 ${persistentSummary.completedCount} · 未知 ${persistentSummary.unknownCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "判定：接受 ${persistentSummary.acceptCount} · 拒绝 ${persistentSummary.rejectCount} · 未决 ${persistentSummary.undecidedCount} · 绑定未知 ${persistentSummary.bindingUnknownCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Judge 尝试 ${persistentSummary.judgeAttemptCount} 次 · 累计耗时 ${persistentSummary.latencyMs?.let { "${it}ms" } ?: "未知"} · Tokens ${persistentSummary.totalTokens ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (persistentSummary.failureCounts.isNotEmpty()) {
                    Text(
                        "Judge 失败分布：${persistentSummary.failureCounts.entries.sortedBy { it.key.ordinal }.joinToString { (kind, count) -> "${kind.toChineseLabel()} $count" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "最多保留 ${KnowledgeAnswerabilityShadowPersistentSummary.MAX_RETAINED_OBSERVATIONS} 条；只包含不可逆指纹、状态枚举和数值遥测，不包含消息或 Run ID、问题、答案、引用、原始响应、URL 或密钥。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "本进程样本摘要",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "样本 ${sampleSummary.sampleCount} · 完成 ${sampleSummary.completedCount} · 未知 ${sampleSummary.unknownCount} · 跳过 ${sampleSummary.skippedCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "跳过明细：关闭 ${sampleSummary.disabledCount} · 身份不匹配 ${sampleSummary.identityMismatchCount} · 来源不支持 ${sampleSummary.unsupportedOriginCount} · 无候选 ${sampleSummary.candidateMissingCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "旁路状态：答案保存失败 ${sampleSummary.answerPersistenceFailedCount} · Shadow Store 失败 ${sampleSummary.shadowStoreFailedCount} · 绑定未知 ${sampleSummary.bindingUnknownCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Judge 尝试 ${sampleSummary.judgeAttemptCount} 次 · 取消 ${sampleSummary.cancelledCount} · 异常 ${sampleSummary.unexpectedCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "累计成本：耗时 ${sampleSummary.latencyMs?.let { "${it}ms" } ?: "未知"} · TTFB ${sampleSummary.firstByteLatencyMs?.let { "${it}ms" } ?: "未知"} · Prompt ${sampleSummary.promptBytes?.let { "${it}B" } ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "累计 Tokens：输入 ${sampleSummary.inputTokens ?: "未知"} · 输出 ${sampleSummary.outputTokens ?: "未知"} · 总计 ${sampleSummary.totalTokens ?: "未知"} · usage attempts ${sampleSummary.usageSampleCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "notice：发布 ${sampleSummary.noticesPublishedCount} · 当前有效 ${sampleSummary.activeNoticeCount} · 已裁剪 ${sampleSummary.noticesPrunedCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (sampleSummary.failureCounts.isNotEmpty()) {
                    Text(
                        "Judge 失败分布：${sampleSummary.failureCounts.entries.sortedBy { it.key.ordinal }.joinToString { (kind, count) -> "${kind.toChineseLabel()} $count" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "本卡片仅保存在当前进程内，重启后清空；notice 不会从历史消息恢复。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun KnowledgeAnswerabilityJudgeFailureKind.toChineseLabel(): String = when (this) {
    KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK -> "网络"
    KnowledgeAnswerabilityJudgeFailureKind.RATE_LIMIT -> "限流"
    KnowledgeAnswerabilityJudgeFailureKind.SERVER -> "服务端"
    KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL -> "协议"
    KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION -> "认证"
    KnowledgeAnswerabilityJudgeFailureKind.CLIENT_REQUEST -> "请求"
    KnowledgeAnswerabilityJudgeFailureKind.IDENTITY -> "身份"
    KnowledgeAnswerabilityJudgeFailureKind.MODEL -> "模型"
    KnowledgeAnswerabilityJudgeFailureKind.INVALID_CANDIDATE -> "候选"
    KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED -> "异常"
}
