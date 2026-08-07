package com.longdev.xiaoling.ui.conversation

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.model.ImageAttachment
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.ui.ChatMessage
import com.longdev.xiaoling.ui.KnowledgeReferencesContent
import com.longdev.xiaoling.ui.knowledgeReferencesForDisplay
import com.longdev.xiaoling.ui.localNoteIdForNavigation
import com.longdev.xiaoling.ui.inspectedTaskNameForNavigation
import com.longdev.xiaoling.ui.normalizeModelMarkdown
import com.longdev.xiaoling.ui.parseMarkdownTableBlock
import com.longdev.xiaoling.ui.theme.LocalChatBubblePalette
import com.longdev.xiaoling.ui.toPresentation
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatBubble(
    message: ChatMessage,
    knowledgeReferenceStatuses: Map<KnowledgeReference, KnowledgeReferenceStatus>,
    failedKnowledgeReferenceStatuses: Set<KnowledgeReference>,
    answerabilityNotice: KnowledgeAnswerabilityUserNotice?,
    onOpenKnowledgeDocument: (String) -> Unit,
    onOpenInspectedTask: (String) -> Unit,
    onOpenLocalNote: (String) -> Unit,
    onReuseUserMessage: (String) -> Unit,
) {
    val isUser = message.role == "user"
    val isError = message.role == "error"
    val clipboardManager = LocalClipboardManager.current
    var actionMenuExpanded by remember { mutableStateOf(false) }
    val palette = LocalChatBubblePalette.current
    val containerColor = when {
        isUser -> palette.userContainer
        isError -> palette.errorContainer
        else -> palette.assistantContainer
    }
    val contentColor = when {
        isUser -> palette.userContent
        isError -> palette.errorContent
        else -> palette.assistantContent
    }
    val borderColor = when {
        isUser -> palette.userBorder
        isError -> palette.errorBorder
        else -> palette.assistantBorder
    }
    val metaColor = when {
        isUser -> palette.userMeta
        isError -> palette.errorMeta
        else -> palette.assistantMeta
    }
    val bubbleShape = RoundedCornerShape(10.dp)
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = bubbleShape,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (isUser) actionMenuExpanded = true
                    },
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
                MessageBodyParts(
                    message = message,
                    contentColor = contentColor,
                    onOpenInspectedTask = onOpenInspectedTask,
                    onOpenLocalNote = onOpenLocalNote,
                )
                KnowledgeReferencesContent(
                    messageId = message.id,
                    references = message.knowledgeReferencesForDisplay(),
                    statuses = knowledgeReferenceStatuses,
                    failedReferences = failedKnowledgeReferenceStatuses,
                    answerabilityNotice = answerabilityNotice,
                    contentColor = contentColor,
                    onOpenDocument = onOpenKnowledgeDocument,
                )
                message.footerLabel()?.let { footer ->
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = footer,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                        color = metaColor,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = actionMenuExpanded,
            onDismissRequest = { actionMenuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("复制", style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.text))
                    actionMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("填入重发", style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    onReuseUserMessage(message.text)
                    actionMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun MessageBodyParts(
    message: ChatMessage,
    contentColor: Color,
    onOpenInspectedTask: (String) -> Unit,
    onOpenLocalNote: (String) -> Unit,
) {
    message.effectiveParts().forEachIndexed { index, part ->
        if (index > 0) Spacer(Modifier.height(7.dp))
        when (part) {
            is MessagePart.Text -> MessageTextPart(message, part.text, contentColor)
            is MessagePart.Reasoning -> ReasoningMessagePartContent(part, contentColor)
            is MessagePart.Image -> ImageMessagePartContent(part)
            is MessagePart.Document -> DocumentMessagePartContent(part, contentColor)
            is MessagePart.Tool -> ToolMessagePartContent(
                part = part,
                contentColor = contentColor,
                onOpenInspectedTask = onOpenInspectedTask,
                onOpenLocalNote = onOpenLocalNote,
            )
        }
    }
}

@Composable
internal fun ImageMessagePartContent(part: MessagePart.Image) {
    ImageAttachmentPreview(
        attachment = part.attachment,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(6.dp)),
    )
}

@Composable
internal fun DocumentMessagePartContent(
    part: MessagePart.Document,
    contentColor: Color,
) {
    val attachment = part.attachment
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(contentColor.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val budgetLabel = attachment.pageCount?.let { "$it 页" }
                ?: attachment.characterCount?.let { "$it 字符" }
                ?: "已验证"
            Text(
                text = "${attachment.mimeType} · ${formatAttachmentSize(attachment.byteSize)} · $budgetLabel",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = contentColor.copy(alpha = 0.72f),
            )
        }
    }
}

internal fun formatAttachmentSize(byteSize: Int): String {
    return if (byteSize >= 1024 * 1024) {
        "${"%.1f".format(Locale.US, byteSize / 1024.0 / 1024.0)} MB"
    } else {
        "${((byteSize + 1023) / 1024).coerceAtLeast(1)} KB"
    }
}

@Composable
internal fun ImageAttachmentPreview(
    attachment: ImageAttachment,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(attachment) { decodeImagePreview(attachment.copyData(), maxDimension = 1_280) }
    if (bitmap == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text("图片无法显示", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = attachment.fileName,
        contentScale = contentScale,
        modifier = modifier,
    )
}

private fun decodeImagePreview(data: ByteArray, maxDimension: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    // long: Room 保存原图是为了请求和备份保真；界面只解码屏幕级缩略图，避免高分辨率附件按原始像素占用大量堆内存。
    return BitmapFactory.decodeByteArray(
        data,
        0,
        data.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

@Composable
internal fun ReasoningMessagePartContent(
    part: MessagePart.Reasoning,
    contentColor: Color,
) {
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    HorizontalDivider(color = contentColor.copy(alpha = 0.16f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(top = 7.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "推理摘要",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "供应商提供",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = contentColor.copy(alpha = 0.68f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDropDown,
            contentDescription = if (expanded) "收起推理摘要" else "展开推理摘要",
            tint = contentColor.copy(alpha = 0.78f),
            modifier = Modifier.size(16.dp),
        )
    }
    if (expanded) {
        StreamingMarkdownText(
            markdown = part.text,
            contentColor = contentColor.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun MessageTextPart(
    message: ChatMessage,
    text: String,
    contentColor: Color,
) {
    if (message.role == "user") {
        Text(text = text, style = MaterialTheme.typography.bodySmall)
        return
    }
    if (message.isStreamingInProgress()) {
        // long: 流式增量会让 Markdown AST 在“半截标题、半截表格、半截代码块”之间频繁变化，实时交给 Markdown 组件会反复重排闪烁；流式中先稳定展示文本，完成后再完整渲染 Markdown。
        Text(
            text = normalizeModelMarkdown(text),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
        return
    }
    StreamingMarkdownText(markdown = text, contentColor = contentColor)
}

@Composable
private fun ToolMessagePartContent(
    part: MessagePart.Tool,
    contentColor: Color,
    onOpenInspectedTask: (String) -> Unit,
    onOpenLocalNote: (String) -> Unit,
) {
    val presentation = part.toPresentation()
    HorizontalDivider(color = contentColor.copy(alpha = 0.16f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Memory,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "工具 · ${presentation.toolName}",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = presentation.statusLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = contentColor.copy(alpha = 0.78f),
        )
    }
    presentation.argumentsLabel?.let { arguments ->
        Text(
            text = "参数 · $arguments",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = contentColor.copy(alpha = 0.76f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        text = "结果 · ${presentation.result}",
        style = MaterialTheme.typography.bodySmall,
        color = contentColor,
        modifier = Modifier.padding(top = 4.dp),
    )
    presentation.memoryLabel?.let { memoryLabel ->
        Text(
            text = memoryLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = contentColor.copy(alpha = 0.76f),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    part.inspectedTaskNameForNavigation()?.let { taskName ->
        TextButton(
            onClick = { onOpenInspectedTask(taskName) },
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ListAlt,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Text("查看任务")
        }
    }
    part.localNoteIdForNavigation()?.let { noteId ->
        TextButton(
            onClick = { onOpenLocalNote(noteId) },
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Text("查看笔记")
        }
    }
}

internal fun ChatMessage.isStreamingInProgress(): Boolean {
    val messageMeta = meta ?: return false
    return role == "assistant" &&
        messageMeta.streaming == true &&
        messageMeta.latencyMs == null &&
        messageMeta.finishReason == null &&
        messageMeta.errorKind == null
}

private fun ChatMessage.footerLabel(): String? {
    return when (role) {
        "user" -> createdAt.toFullTimeLabel()
        "assistant" -> assistantFooterLabel() ?: createdAt.toFullTimeLabel()
        "error" -> "请求失败 · ${createdAt.toFullTimeLabel()}"
        else -> createdAt.toFullTimeLabel()
    }
}

internal fun ChatMessage.assistantFooterLabel(): String? {
    val messageMeta = meta ?: return null
    val latency = messageMeta.latencyMs
    val firstTokenLatency = messageMeta.firstTokenLatencyMs
    return when {
        messageMeta.finishReason == "failed" ->
            "内容不完整 · ${messageMeta.errorKind ?: "请求失败"}"
        messageMeta.finishReason == "cancelled" ->
            "已停止${firstTokenLatency?.let { " · 首字 ${it.toSecondsText()}" }.orEmpty()}"
        messageMeta.streaming == true && firstTokenLatency != null && latency != null ->
            "首字 ${firstTokenLatency.toSecondsText()} · 耗时 ${latency.toSecondsText()}"
        messageMeta.streaming == true && latency != null ->
            "首字 - · 耗时 ${latency.toSecondsText()}"
        messageMeta.streaming == true && firstTokenLatency != null ->
            "首字 ${firstTokenLatency.toSecondsText()} · 接收中"
        latency != null ->
            "耗时 ${latency.toSecondsText()}"
        else -> null
    }
}

private fun Long.toSecondsText(): String = String.format(Locale.US, "%.2f s", this / 1000.0)

internal fun Long.toFullTimeLabel(): String {
    return SimpleDateFormat(FULL_TIME_PATTERN, Locale.getDefault()).format(Date(this))
}

@Composable
private fun StreamingMarkdownText(
    markdown: String,
    contentColor: Color,
) {
    val normalizedMarkdown = normalizeModelMarkdown(markdown)
    // long: 模型输出会覆盖表格、链接、引用、嵌套列表等常见 Markdown；继续维护自研解析器会让每一种语法都变成补丁，这里交给 GFM 渲染库统一处理。
    Markdown(
        content = normalizedMarkdown.ifBlank { " " },
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(
            text = contentColor,
            codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
            inlineCodeBackground = contentColor.copy(alpha = 0.10f),
            dividerColor = contentColor.copy(alpha = 0.18f),
            tableBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.46f),
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.bodySmall.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
            h2 = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
            h3 = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
            h4 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            h5 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            h6 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            text = MaterialTheme.typography.bodySmall,
            paragraph = MaterialTheme.typography.bodySmall,
            ordered = MaterialTheme.typography.bodySmall,
            bullet = MaterialTheme.typography.bodySmall,
            list = MaterialTheme.typography.bodySmall,
            textLink = TextLinkStyles(
                // long: Markdown 库默认链接使用 bodyLarge + Bold，Sources 这类引用列表会被放大成标题感；这里强制跟随正文小字号，只保留下划线表示可点击链接。
                style = SpanStyle(
                    color = contentColor,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = FontWeight.Normal,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
            table = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
            inlineCode = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
        ),
        imageTransformer = Coil3ImageTransformerImpl,
        components = markdownComponents(
            codeBlock = highlightedCodeBlock,
            codeFence = highlightedCodeFence,
            table = { model ->
                val tableStart = model.node.startOffset.coerceIn(0, model.content.length)
                val tableEnd = model.node.endOffset.coerceIn(tableStart, model.content.length)
                BorderedMarkdownTable(
                    rawTable = model.content.substring(tableStart, tableEnd),
                    contentColor = contentColor,
                )
            },
        ),
        loading = { Box(it) },
        error = {
            Text(
                text = normalizedMarkdown,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = it,
            )
        },
    )
}

@Composable
private fun BorderedMarkdownTable(
    rawTable: String,
    contentColor: Color,
) {
    val table = remember(rawTable) { parseMarkdownTableBlock(rawTable) }
    if (table == null) {
        Text(
            text = rawTable,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
        return
    }

    val borderColor = contentColor.copy(alpha = 0.20f)
    val tableShape = RoundedCornerShape(6.dp)
    val cellWidth = markdownTableCellWidth(table.columnCount)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(tableShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
            .border(1.dp, borderColor, tableShape),
    ) {
        BorderedMarkdownTableRow(
            cells = table.headers,
            columnCount = table.columnCount,
            alignments = table.alignments,
            cellWidth = cellWidth,
            borderColor = borderColor,
            contentColor = contentColor,
            header = true,
        )
        table.rows.forEachIndexed { index, row ->
            BorderedMarkdownTableRow(
                cells = row,
                columnCount = table.columnCount,
                alignments = table.alignments,
                cellWidth = cellWidth,
                borderColor = borderColor,
                contentColor = contentColor,
                header = false,
                rowIndex = index,
            )
        }
    }
}

@Composable
private fun BorderedMarkdownTableRow(
    cells: List<String>,
    columnCount: Int,
    alignments: List<TextAlign>,
    cellWidth: Dp,
    borderColor: Color,
    contentColor: Color,
    header: Boolean,
    rowIndex: Int = 0,
) {
    Row {
        repeat(columnCount) { columnIndex ->
            val cellText = cells.getOrNull(columnIndex).orEmpty()
            val textAlign = alignments.getOrNull(columnIndex) ?: TextAlign.Start
            val cellBackground = when {
                header -> contentColor.copy(alpha = 0.08f)
                rowIndex % 2 == 1 -> contentColor.copy(alpha = 0.035f)
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .width(cellWidth)
                    .heightIn(min = if (header) 34.dp else 32.dp)
                    .background(cellBackground)
                    .border(0.6.dp, borderColor)
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                contentAlignment = when (textAlign) {
                    TextAlign.Center -> Alignment.Center
                    TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                },
            ) {
                // long: Markdown 表格在模型回复里主要承担结构化数据阅读，单元格边框和交替底色比装饰更重要；保持小字号和可横向滚动，避免多列表格挤压对话气泡。
                Text(
                    text = cellText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = textAlign,
                    ),
                    color = contentColor,
                )
            }
        }
    }
}

private fun markdownTableCellWidth(columnCount: Int): Dp = when {
    columnCount <= 2 -> 136.dp
    columnCount == 3 -> 108.dp
    else -> 92.dp
}

private const val FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
