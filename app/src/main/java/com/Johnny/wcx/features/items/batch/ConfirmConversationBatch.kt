package com.Johnny.wcx.features.items.batch

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog

internal fun confirmConversationBatch(
    context: Context,
    title: String,
    rows: List<BatchConversation>,
    description: String,
    actionLabel: String,
    onConfirm: () -> Unit,
) = showComposeDialog(context) {
    var confirmed by remember { mutableStateOf(false) }
    AlertDialogContent(
        title = { Text("$title · ${rows.size} 项") },
        text = { Column {
            Text(description)
            LazyColumn(Modifier.heightIn(max = 255.dp)) {
                items(rows, key = { it.wxId }) { row -> Text("${row.displayName} · ${row.category.label}") }
            }
        } },
        dismissButton = { TextButton(onDismiss) { Text("返回") } },
        confirmButton = {
            Button(enabled = !confirmed, onClick = {
                if (!confirmed) {
                    confirmed = true
                    onConfirm()
                    onDismiss()
                }
            }) { Text(actionLabel) }
        },
    )
}
