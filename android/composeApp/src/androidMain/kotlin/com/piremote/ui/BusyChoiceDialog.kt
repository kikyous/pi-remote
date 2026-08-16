package com.piremote.ui

import com.piremote.R

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
        title = { Text(stringResource(R.string.busy_title)) },
        text = {
            val shown = message.ifBlank { stringResource(R.string.busy_image_placeholder) }
            Text(
                stringResource(R.string.busy_message, shown),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSteer(); onDismiss() }) { Text(stringResource(R.string.busy_interrupt)) }
        },
        dismissButton = {
            TextButton(onClick = { onQueue(); onDismiss() }) { Text(stringResource(R.string.busy_queue)) }
        },
    )
}
