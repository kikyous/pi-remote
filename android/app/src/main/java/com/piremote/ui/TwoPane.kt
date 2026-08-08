package com.piremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tablet layout: list on the left, conversation on the right.
 *
 * Keeping both mounted is the point — switching sessions becomes an instant
 * swap of the right pane instead of a navigation, and the list never loses its
 * scroll position.
 */
@Composable
fun TwoPaneLayout(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        Box(Modifier.width(LIST_PANE_WIDTH).fillMaxHeight()) { list() }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (detail != null) {
                detail()
            } else {
                Text(
                    "选一个会话开始",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** Wide enough for a session's opening line to be readable without wrapping. */
private val LIST_PANE_WIDTH = 340.dp
