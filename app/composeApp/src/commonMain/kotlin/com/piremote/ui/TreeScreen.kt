@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.piremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.data.AppRepository
import com.piremote.net.ApiException
import com.piremote.net.SessionTreeDto
import com.piremote.net.TreeNodeDto
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import piremote.composeapp.generated.resources.*

private val RAIL_SLOT_WIDTH = 20.dp
private val ROW_MIN_HEIGHT = 44.dp

@Composable
fun TreeScreen(
    repo: AppRepository,
    sessionId: String,
    onBack: () -> Unit,
    /**
     * The leaf moved. [editorText] is the selected user message's text, to be
     * offered for editing; null when the target was not a user message and the
     * composer should be left alone.
     */
    onMoved: (editorText: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val loadFailed = stringResource(Res.string.tree_load_failed)
    val navigateFailed = stringResource(Res.string.err_navigate)
    val busy = stringResource(Res.string.err_navigate_busy)
    val unsupported = stringResource(Res.string.err_navigate_unsupported)

    var tree by remember { mutableStateOf<SessionTreeDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filterName by rememberSaveable { mutableStateOf(TreeFilter.Default.name) }
    var foldedIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var pendingRow by remember { mutableStateOf<TreeRow?>(null) }
    var moving by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var initialScrolled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        try {
            tree = repo.client.sessionTree(sessionId)
            error = null
        } catch (e: ApiException) {
            error = if (e.status == 501 || e.status == 404) unsupported else e.message.ifBlank { loadFailed }
        } catch (e: Exception) {
            error = e.message ?: loadFailed
        }
    }

    val nodes = tree?.nodes.orEmpty()
    val leafId = tree?.leafId
    val filter = remember(filterName) { TreeFilter.valueOf(filterName) }
    val rows = remember(nodes, leafId, filter, foldedIds) {
        buildTreeRows(nodes, leafId, filter, foldedIds)
    }

    LaunchedEffect(rows) {
        if (!initialScrolled && rows.isNotEmpty()) {
            val leafIdx = rows.indexOfFirst { it.isLeaf }
            if (leafIdx >= 0) {
                listState.scrollToItem(leafIdx)
                initialScrolled = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                title = { Text(stringResource(Res.string.tree_title), style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (foldedIds.isNotEmpty()) {
                        TextButton(onClick = { foldedIds = emptySet() }) {
                            Text(
                                stringResource(Res.string.tool_expand_all, foldedIds.size),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    FilterChip(
                        selected = true,
                        onClick = { filterName = filter.next.name },
                        label = {
                            Text(
                                filterLabel(filter),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                error != null -> Centered(
                    error!!,
                    MaterialTheme.colorScheme.error,
                    Modifier.align(Alignment.Center),
                )

                tree == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                rows.isEmpty() -> Centered(
                    stringResource(Res.string.tree_empty),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.align(Alignment.Center),
                )

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(rows, key = { it.node.id }) { row ->
                            TreeRowItem(
                                row = row,
                                onToggleFold = {
                                    foldedIds = if (row.node.id in foldedIds) {
                                        foldedIds - row.node.id
                                    } else {
                                        foldedIds + row.node.id
                                    }
                                },
                                onClick = { pendingRow = row },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (rows.size > 5 || rows.any { it.showConnector || it.isFoldable }) {
                        FloatingBranchNavigator(
                            rows = rows,
                            listState = listState,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 24.dp),
                        )
                    }
                }
            }

            if (moving) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    pendingRow?.let { row ->
        MoveDialog(
            node = row.node,
            isFoldable = row.isFoldable,
            isFolded = row.isFolded,
            onToggleFold = {
                foldedIds = if (row.node.id in foldedIds) {
                    foldedIds - row.node.id
                } else {
                    foldedIds + row.node.id
                }
            },
            onDismiss = { pendingRow = null },
            onConfirm = {
                val targetId = row.node.id
                pendingRow = null
                moving = true
                scope.launch {
                    try {
                        onMoved(repo.client.navigateTree(sessionId, targetId).editorText)
                    } catch (e: ApiException) {
                        error = when {
                            e.isBusy -> busy
                            e.status == 501 || e.status == 404 -> unsupported
                            else -> e.message.ifBlank { navigateFailed }
                        }
                    } catch (e: Exception) {
                        error = e.message ?: navigateFailed
                    } finally {
                        moving = false
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    onToggleFold: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val node = row.node
    val isUser = node.kind == "user"

    val backgroundColor = when {
        row.isLeaf -> scheme.primaryContainer.copy(alpha = 0.25f)
        row.isOnActivePath -> scheme.surfaceVariant.copy(alpha = 0.35f)
        else -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(backgroundColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (row.isFoldable) onToggleFold else null,
                onDoubleClick = if (row.isFoldable) onToggleFold else null,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Guide rails and tree connectors - extends 100% height from top to bottom edge
        TreeRails(
            indent = row.indent,
            showConnector = row.showConnector,
            isLast = row.isLast,
            gutters = row.gutters,
            isFoldable = row.isFoldable,
            isFolded = row.isFolded,
            onToggleFold = onToggleFold,
            modifier = Modifier.fillMaxHeight(),
        )

        Spacer(Modifier.width(4.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Active path marker
            if (row.isOnActivePath) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
                Spacer(Modifier.width(6.dp))
            }

            // Label if present
            node.label?.let { label ->
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = scheme.tertiaryContainer,
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = scheme.onTertiaryContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }

            // Role prefix + content, matching TUI style
            when (node.kind) {
                "user" -> {
                    Text(
                        text = "user: ",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = scheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = node.text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = if (row.isOnActivePath) scheme.onSurface else scheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                "assistant" -> {
                    Text(
                        text = "assistant: ",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = scheme.secondary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = node.text.ifBlank { kindLabel(node.kind) },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = if (row.isOnActivePath) scheme.onSurface else scheme.onSurfaceVariant,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                else -> {
                    val rawText = node.text.ifBlank { kindLabel(node.kind) }
                    val displayText = when (node.kind) {
                        "bash" -> if (rawText.startsWith("[") && rawText.endsWith("]")) rawText else "[bash: $rawText]"
                        else -> if (rawText.startsWith("[") && rawText.endsWith("]")) rawText else "[$rawText]"
                    }
                    val textColor = when (node.kind) {
                        "bash" -> scheme.tertiary.copy(alpha = 0.9f)
                        else -> scheme.onSurfaceVariant.copy(alpha = 0.8f)
                    }
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = textColor,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // Trailing status (Current leaf badge)
            if (row.isLeaf) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.tree_current),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = scheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Draws the guide rails (gutters) and connector lines (`│`, `├─`, `└─`) matching TUI.
 */
@Composable
private fun TreeRails(
    indent: Int,
    showConnector: Boolean,
    isLast: Boolean,
    gutters: List<Gutter>,
    isFoldable: Boolean,
    isFolded: Boolean,
    onToggleFold: () -> Unit,
    modifier: Modifier = Modifier,
    slotWidth: Dp = RAIL_SLOT_WIDTH,
) {
    val scheme = MaterialTheme.colorScheme
    val railColor = scheme.outlineVariant.copy(alpha = 0.9f)

    // Width is strictly indent * slotWidth.
    // When a root node (indent == 0) is folded, reserve 1 slot to show the [+] button.
    val railWidth = if (indent > 0) {
        (indent * slotWidth.value).dp
    } else if (isFoldable && isFolded) {
        slotWidth
    } else {
        0.dp
    }

    if (railWidth == 0.dp && (!isFoldable || !isFolded)) {
        return
    }

    Box(
        modifier = modifier
            .width(railWidth)
            .defaultMinSize(minHeight = ROW_MIN_HEIGHT),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val slotPx = slotWidth.toPx()
            val strokeWidth = 2.dp.toPx()
            val centerY = size.height / 2f

            // 1. Ancestor vertical rails (gutters)
            for (gutter in gutters) {
                if (gutter.show) {
                    val gx = (gutter.position + 0.5f) * slotPx
                    drawLine(
                        color = railColor,
                        start = Offset(gx, 0f),
                        end = Offset(gx, size.height),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Square,
                    )
                }
            }

            // 2. Incoming connector (├─ or └─) in slot (indent - 1)
            if (showConnector && indent > 0) {
                val cx = (indent - 1 + 0.5f) * slotPx

                // Top to center
                drawLine(
                    color = railColor,
                    start = Offset(cx, 0f),
                    end = Offset(cx, centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Square,
                )

                // Center to bottom if not last sibling
                if (!isLast) {
                    drawLine(
                        color = railColor,
                        start = Offset(cx, centerY),
                        end = Offset(cx, size.height),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Square,
                    )
                }

                // Horizontal connector to node
                drawLine(
                    color = railColor,
                    start = Offset(cx, centerY),
                    end = Offset(indent * slotPx, centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Square,
                )
            }
        }

        // Fold toggle button:
        // Sits inside slot (indent - 1) on the connector line (or slot 0 if folded root),
        // matching TUI where ⊟/⊞ replaces the horizontal dash of ├─ / └─.
        if (isFoldable && (indent > 0 || isFolded)) {
            val buttonSlot = if (indent > 0) indent - 1 else 0
            val foldOffset = (buttonSlot * slotWidth.value + (slotWidth.value - 14f) / 2f).dp
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = scheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, scheme.outlineVariant),
                modifier = Modifier
                    .padding(start = foldOffset)
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(onClick = onToggleFold),
            ) {
                Icon(
                    imageVector = if (isFolded) Icons.Default.Add else Icons.Default.Remove,
                    contentDescription = if (isFolded) "Expand" else "Collapse",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(0.5.dp),
                )
            }
        }
    }
}

@Composable
private fun filterLabel(filter: TreeFilter): String = when (filter) {
    TreeFilter.Default -> stringResource(Res.string.tree_filter_default)
    TreeFilter.NoTools -> stringResource(Res.string.tree_filter_no_tools)
    TreeFilter.UserOnly -> stringResource(Res.string.tree_filter_user_only)
    TreeFilter.LabeledOnly -> stringResource(Res.string.tree_filter_labeled_only)
    TreeFilter.All -> stringResource(Res.string.tree_filter_all)
}

@Composable
private fun Centered(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier.padding(32.dp),
    )
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "user" -> stringResource(Res.string.tree_kind_user)
    "assistant" -> stringResource(Res.string.tree_kind_assistant)
    "tool" -> stringResource(Res.string.tree_kind_tool)
    "bash" -> stringResource(Res.string.tree_kind_bash)
    "compaction" -> stringResource(Res.string.notice_compacted)
    "branch" -> stringResource(Res.string.notice_branch_summary)
    "model" -> stringResource(Res.string.tree_kind_model)
    "thinking" -> stringResource(Res.string.tree_kind_thinking)
    "named" -> stringResource(Res.string.tree_kind_named)
    else -> kind
}

@Composable
private fun MoveDialog(
    node: TreeNodeDto,
    isFoldable: Boolean = false,
    isFolded: Boolean = false,
    onToggleFold: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tree_jump_title)) },
        text = {
            Column {
                Text(
                    node.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        if (node.kind == "user") Res.string.tree_jump_user else Res.string.tree_jump_other,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.tree_jump_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.tree_jump_confirm)) } },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isFoldable && onToggleFold != null) {
                    TextButton(onClick = {
                        onToggleFold()
                        onDismiss()
                    }) {
                        Text(
                            stringResource(if (isFolded) Res.string.tree_unfold_branch else Res.string.tree_fold_branch),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}

@Composable
private fun FloatingBranchNavigator(
    rows: List<TreeRow>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainerHigh.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            // Jump to previous branch segment start
            IconButton(
                onClick = {
                    val current = listState.firstVisibleItemIndex
                    val target = (current - 1 downTo 0).firstOrNull {
                        rows[it].isFoldable || rows[it].showConnector
                    } ?: 0
                    scope.launch { listState.animateScrollToItem(target) }
                },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(Res.string.tree_prev_branch),
                    tint = scheme.onSurface,
                )
            }

            // Jump to active leaf position
            IconButton(
                onClick = {
                    val target = rows.indexOfFirst { it.isLeaf }
                        .takeIf { it >= 0 }
                        ?: rows.indexOfLast { it.isOnActivePath }.takeIf { it >= 0 }
                        ?: (rows.size - 1)
                    if (target in rows.indices) {
                        scope.launch { listState.animateScrollToItem(target) }
                    }
                },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    Icons.Default.CenterFocusStrong,
                    contentDescription = stringResource(Res.string.tree_jump_leaf),
                    tint = scheme.primary,
                )
            }

            // Jump to next branch segment start
            IconButton(
                onClick = {
                    val current = listState.firstVisibleItemIndex
                    val target = (current + 1 until rows.size).firstOrNull {
                        rows[it].isFoldable || rows[it].showConnector
                    } ?: (rows.size - 1)
                    scope.launch { listState.animateScrollToItem(target) }
                },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(Res.string.tree_next_branch),
                    tint = scheme.onSurface,
                )
            }
        }
    }
}
