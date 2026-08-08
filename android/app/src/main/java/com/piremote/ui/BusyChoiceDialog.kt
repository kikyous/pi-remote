package com.piremote.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

/**
 * Offered when the agent is mid-turn and a message is sent anyway.
 *
 * pi requires the caller to choose: interrupt the current turn, or wait for it.
 * Rather than pick one silently, ask — the two produce quite different results,
 * and the server rejects the prompt outright without a choice.
 */
@Composable
fun BusyChoiceDialog(
    message: String,
    onSteer: () -> Unit,
    onQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话正在运行") },
        text = {
            Text(
                "「$message」\n\n插队会在当前这一轮的工具调用结束后立刻送达；排队则等整轮跑完再送。",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSteer(); onDismiss() }) { Text("插队") }
        },
        dismissButton = {
            TextButton(onClick = { onQueue(); onDismiss() }) { Text("排队") }
        },
    )
}
