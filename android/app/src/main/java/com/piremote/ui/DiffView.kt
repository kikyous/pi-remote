package com.piremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Shared red/green diff line rendering, used by the edit-tool card and the
 * git file-diff screen.
 */

enum class DiffKind { Added, Removed, Context }

data class DiffRow(val text: String, val kind: DiffKind)

/** GitHub diff palette, theme-aware: bright on dark, deep on light. */
@Composable
fun diffPalette(): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        Color(0xFF7EE787) to Color(0xFFFF7B72) // added, removed
    } else {
        Color(0xFF1A7F37) to Color(0xFFCF222E) // GitHub light: deep green, deep red
    }
}

@Composable
fun DiffLine(row: DiffRow, modifier: Modifier = Modifier) {
    val (added, removed) = diffPalette()
    val (bg, fg, prefix) = when (row.kind) {
        DiffKind.Added -> Triple(added.copy(alpha = 0.16f), added, "+")
        DiffKind.Removed -> Triple(removed.copy(alpha = 0.16f), removed, "-")
        DiffKind.Context -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurfaceVariant, " ")
    }
    Text(
        "$prefix ${row.text}",
        style = MonoStyle,
        color = fg,
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Line diff: common prefix, common suffix, the middle as removed → added.
 *
 * A full Myers diff is overkill for tool hunks: trimming the ends then showing
 * the middle reads exactly like a real edit for the sizes pi actually produces.
 */
fun simpleLineDiff(oldLines: List<String>, newLines: List<String>): List<DiffRow> {
    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
    var suffix = 0
    while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
        oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
    ) suffix++

    val rows = mutableListOf<DiffRow>()
    for (i in 0 until prefix) rows += DiffRow(oldLines[i], DiffKind.Context)
    for (i in prefix until oldLines.size - suffix) rows += DiffRow(oldLines[i], DiffKind.Removed)
    for (i in prefix until newLines.size - suffix) rows += DiffRow(newLines[i], DiffKind.Added)
    for (i in 0 until suffix) rows += DiffRow(oldLines[oldLines.size - suffix + i], DiffKind.Context)
    return rows
}
