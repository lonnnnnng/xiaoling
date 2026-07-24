package com.longdev.xiaoling.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceProductionIdentityBinding
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceProductionIdentityPolicy
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceProductionIdentityStatus
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceRolloutMode
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceRolloutPreference
import com.longdev.xiaoling.storage.UiPreferenceStore

@Composable
internal fun KnowledgeRelevanceRolloutSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { UiPreferenceStore(context.applicationContext) }
    var binding by remember { mutableStateOf(store.loadKnowledgeRelevanceProductionIdentityBinding()) }
    var preference by remember { mutableStateOf(store.loadKnowledgeRelevanceRolloutPreference()) }
    KnowledgeRelevanceRolloutStatusContent(
        binding = binding,
        preference = preference,
        onRollback = {
            // long: 回滚同时撤销执行资格和身份状态，历史检索与已展示答案不受影响，下一次验证必须从候选身份重新开始。
            store.rollbackKnowledgeRelevanceRollout()
            store.saveKnowledgeRelevanceProductionIdentityBinding(
                KnowledgeRelevanceProductionIdentityPolicy.revoke(binding),
            )
            binding = store.loadKnowledgeRelevanceProductionIdentityBinding()
            preference = store.loadKnowledgeRelevanceRolloutPreference()
        },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun KnowledgeRelevanceRolloutStatusContent(
    binding: KnowledgeRelevanceProductionIdentityBinding,
    preference: KnowledgeRelevanceRolloutPreference,
    onRollback: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val identity = binding.identity
    val canRollback = binding.status != KnowledgeRelevanceProductionIdentityStatus.UNBOUND ||
        preference.enforcementEnabled
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("knowledge-relevance-rollout-status"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
            }
            Text("相关性灰度控制面", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("当前状态", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text("身份：${binding.status.toLabel()}", modifier = Modifier.testTag("knowledge-relevance-identity-status"))
                Text("有效模式：${KnowledgeRelevanceRolloutMode.SHADOW.name}（生产答案路径尚未接入）")
                Text("用户开关：${if (preference.enforcementEnabled) "已请求开启" else "关闭"}")
                Text(
                    "候选身份只用于观察；未完成同一 Provider/模型的独立 final holdout 前，不会进入 enforcement。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("绑定身份", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text("Provider：${identity?.providerId ?: "未绑定"}")
                Text("模型：${identity?.model ?: "未绑定"}")
                Text("配置指纹：${identity?.configurationFingerprint ?: "未绑定"}")
                Text("Gate：${binding.gateVersion ?: "未验证"}")
                Text("证据：${binding.evidenceVersion ?: "未验证"}")
                Text("Holdout：${binding.holdoutDatasetVersion ?: "未验证"}")
            }
        }

        OutlinedButton(
            onClick = onRollback,
            enabled = canRollback,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("knowledge-relevance-rollout-rollback"),
        ) {
            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(17.dp))
            Text("撤销灰度身份", modifier = Modifier.padding(start = 6.dp))
        }
        Text(
            "撤销只清除未来执行资格并保留最小身份审计；重新验证前，相关性检查继续 fail-open。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun KnowledgeRelevanceProductionIdentityStatus.toLabel(): String = when (this) {
    KnowledgeRelevanceProductionIdentityStatus.UNBOUND -> "未绑定"
    KnowledgeRelevanceProductionIdentityStatus.CANDIDATE -> "候选身份"
    KnowledgeRelevanceProductionIdentityStatus.VERIFIED -> "已验证"
    KnowledgeRelevanceProductionIdentityStatus.REVOKED -> "已撤销"
}
