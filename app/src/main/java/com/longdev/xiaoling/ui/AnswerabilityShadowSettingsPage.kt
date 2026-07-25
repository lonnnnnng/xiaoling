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

@Composable
internal fun AnswerabilityShadowSettingsContent(
    enabled: Boolean,
    sampleSummary: KnowledgeAnswerabilityShadowSampleSummary = KnowledgeAnswerabilityShadowSampleSummary(),
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
                            "启用答案可回答性 Shadow",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "仅对前台直接 /agent 答案生效；答案先展示并保存，之后才把问题和知识候选发送给冻结 Judge。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                        modifier = Modifier.semantics { contentDescription = "启用答案可回答性 Shadow" },
                    )
                }
                // long: Shadow 只增加消息旁路提示；Judge 延迟、失败或否决都不会删除引用、改写答案或开启生产 enforcement。
                Text(
                    "当前边界：默认关闭 · 仅匹配冻结 gpt-5.5 身份时请求 · 不进入普通聊天、Workflow 或后台任务 · 不写入 Room 观测表。",
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
                    "仅保存在当前进程内；重启后清空，不包含问题、答案、引用、原始响应或密钥。",
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
