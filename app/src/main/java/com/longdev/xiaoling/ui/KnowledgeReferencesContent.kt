package com.longdev.xiaoling.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceAvailability
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceUserNotice

@Composable
internal fun KnowledgeReferencesContent(
    messageId: String,
    references: List<KnowledgeReference>,
    statuses: Map<KnowledgeReference, KnowledgeReferenceStatus>,
    failedReferences: Set<KnowledgeReference> = emptySet(),
    answerabilityNotice: KnowledgeAnswerabilityUserNotice? = null,
    relevanceNotice: KnowledgeRelevanceUserNotice? = null,
    contentColor: Color,
    onOpenDocument: (KnowledgeReference) -> Unit,
) {
    if (references.isEmpty() && answerabilityNotice == null && relevanceNotice == null) return
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        answerabilityNotice?.let { notice ->
            // long: answerability 只解释候选是否真正回答问题；shadow 阶段即使没有引用也要展示未知或不足原因，但不能因此删改答案。
            KnowledgeNoticeContent(
                testTag = "knowledge-answerability-notice",
                title = notice.title,
                detail = notice.detail,
                contentColor = contentColor,
            )
        }
        relevanceNotice?.let { notice ->
            // long: 低分降级或无可靠知识时，即使没有可展开引用也必须保留解释，避免用户把“没有引用”误解成界面丢失。
            KnowledgeNoticeContent(
                testTag = "knowledge-relevance-notice",
                title = notice.title,
                detail = notice.detail,
                contentColor = contentColor,
            )
        }
        if (references.isEmpty()) return@Column
        HorizontalDivider(
            color = contentColor.copy(alpha = 0.16f),
            modifier = Modifier.padding(top = 7.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(top = 7.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "知识引用 · ${references.size}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDropDown,
                contentDescription = if (expanded) "收起知识引用" else "展开知识引用",
                tint = contentColor.copy(alpha = 0.78f),
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            for (index in references.indices) {
                val reference = references[index]
                key(reference.documentId, reference.chunkId) {
                    if (index > 0) {
                        HorizontalDivider(color = contentColor.copy(alpha = 0.10f))
                    }
                    val presentation = statuses[reference]?.toPresentation()
                        ?: if (reference in failedReferences) {
                            reference.toFailedStatusPresentation()
                        } else {
                            reference.toPendingPresentation()
                        }
                    KnowledgeReferenceRow(
                        reference = reference,
                        presentation = presentation,
                        contentColor = contentColor,
                        onOpenDocument = onOpenDocument,
                    )
                }
            }
        }
    }
}

@Composable
private fun KnowledgeNoticeContent(
    testTag: String,
    title: String,
    detail: String,
    contentColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .padding(top = 7.dp, bottom = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = contentColor.copy(alpha = 0.78f),
        )
    }
}

@Composable
private fun KnowledgeReferenceRow(
    reference: KnowledgeReference,
    presentation: KnowledgeReferencePresentation,
    contentColor: Color,
    onOpenDocument: (KnowledgeReference) -> Unit,
) {
    val modifier = if (presentation.canOpenDocument) {
        Modifier
            .fillMaxWidth()
            .testTag("knowledge-reference-${reference.documentId}")
            .clickable { onOpenDocument(reference) }
            .semantics { contentDescription = "打开知识原文 ${presentation.documentName}" }
    } else {
        Modifier
            .fillMaxWidth()
            .testTag("knowledge-reference-${reference.documentId}")
    }
    Column(
        modifier = modifier.padding(vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = presentation.documentName,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = presentation.statusLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = presentation.statusColor(contentColor),
            )
            if (presentation.canOpenDocument) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.72f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = presentation.locationLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = contentColor.copy(alpha = 0.74f),
            fontFamily = FontFamily.Monospace,
        )
        presentation.statusDetail?.let { detail ->
            // long: 历史引用只展示当前文档元数据，不展示或暗示旧片段仍是现行资料；原 revision/chunk/offset 始终保留供审计。
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = presentation.statusColor(contentColor),
            )
        }
    }
}

@Composable
private fun KnowledgeReferencePresentation.statusColor(contentColor: Color): Color {
    return when (availability) {
        KnowledgeReferenceAvailability.CURRENT -> MaterialTheme.colorScheme.tertiary
        KnowledgeReferenceAvailability.HISTORICAL -> MaterialTheme.colorScheme.secondary
        KnowledgeReferenceAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.error
        null -> contentColor.copy(alpha = 0.68f)
    }
}
