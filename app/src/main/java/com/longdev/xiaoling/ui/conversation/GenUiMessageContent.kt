package com.longdev.xiaoling.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.genui.GenUiDocument
import com.longdev.xiaoling.genui.GenUiItem

@Composable
internal fun GenUiMessageContent(
    document: GenUiDocument,
    contentColor: androidx.compose.ui.graphics.Color,
    onAction: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            document.title?.let { title ->
                Text(
                    text = title,
                    color = contentColor,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            document.items.forEach { item ->
                when (item) {
                    is GenUiItem.Text -> Text(
                        text = item.text,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is GenUiItem.Button -> Button(
                        onClick = { onAction(item.action) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(item.label)
                    }
                }
            }
        }
    }
}
