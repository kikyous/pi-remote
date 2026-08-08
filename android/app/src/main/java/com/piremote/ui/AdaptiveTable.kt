package com.piremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

/**
 * A markdown table whose columns size themselves to their content.
 *
 * The library's default gives every column the same fixed width; this one
 * parses the GFM table AST, measures every cell with [rememberTextMeasurer],
 * and lets the widest cell of each column decide that column's width. The table
 * scrolls horizontally as a whole when it outgrows the card.
 */
@Composable
fun AdaptiveMarkdownTable(model: MarkdownComponentModel) {
    val content = model.content
    val node = model.node
    val style = model.typography.paragraph ?: MaterialTheme.typography.bodyMedium
    val textColor = MaterialTheme.colorScheme.onSurface

    val header = node.findChildOfType(HEADER)
    val bodyRows = node.children.filter { it.type == ROW }
    val columnCount = header?.children?.count { it.type == CELL } ?: 0
    if (columnCount == 0) return

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columnWidths = remember(content, node) {
        val measured = IntArray(columnCount)
        fun scan(cells: List<ASTNode>) {
            cells.forEachIndexed { index, cell ->
                if (index >= columnCount) return@forEachIndexed
                val annotated = content.buildMarkdownAnnotatedString(cell, style)
                measured[index] = maxOf(measured[index], textMeasurer.measure(annotated, style).size.width)
            }
        }
        scan(header?.children?.filter { it.type == CELL } ?: emptyList())
        for (row in bodyRows) scan(row.children.filter { it.type == CELL })
        measured.map { px ->
            with(density) {
                (px + 2 * CELL_PADDING.toPx())
                    .coerceIn(MIN_COL_WIDTH.toPx(), MAX_COL_WIDTH.toPx())
                    .toDp()
            }
        }
    }
    val tableWidth = columnWidths.fold(0.dp) { acc, w -> acc + w }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
    ) {
        val scrollable = maxWidth < tableWidth
        Column(
            Modifier
                .then(
                    if (scrollable) Modifier.horizontalScroll(rememberScrollState()).requiredWidth(tableWidth)
                    else Modifier.fillMaxWidth(),
                )
                .padding(vertical = 2.dp),
        ) {
            if (header != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    header.children.filter { it.type == CELL }.forEachIndexed { index, cell ->
                        TableCell(content, cell, columnWidths.getOrElse(index) { MAX_COL_WIDTH },
                            style.copy(fontWeight = FontWeight.Bold), textColor)
                    }
                }
                HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
            for (row in bodyRows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row.children.filter { it.type == CELL }.forEachIndexed { index, cell ->
                        TableCell(content, cell, columnWidths.getOrElse(index) { MAX_COL_WIDTH }, style, textColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(content: String, cell: ASTNode, width: Dp, style: TextStyle, textColor: androidx.compose.ui.graphics.Color) {
    val annotated = remember(cell, content) { content.buildMarkdownAnnotatedString(cell, style) }
    Text(
        annotated,
        style = style.copy(color = textColor),
        modifier = Modifier.width(width).padding(horizontal = CELL_PADDING, vertical = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val CELL_PADDING = 8.dp
private val MIN_COL_WIDTH = 48.dp
private val MAX_COL_WIDTH = 360.dp
