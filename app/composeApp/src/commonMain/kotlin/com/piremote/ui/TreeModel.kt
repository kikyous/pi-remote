package com.piremote.ui

import com.piremote.net.TreeNodeDto

/**
 * Filter modes for the session tree view, matching pi's TUI.
 */
enum class TreeFilter {
    Default, NoTools, UserOnly, LabeledOnly, All;

    val next: TreeFilter get() = entries[(ordinal + 1) % entries.size]
}

/**
 * A guide line (gutter) for an ancestor's branch column.
 *
 * @property position The 0-based column index where this vertical line sits.
 * @property show Whether to draw the vertical line `│` (true if the ancestor branch has more siblings below).
 */
data class Gutter(val position: Int, val show: Boolean)

/**
 * A single row of the session tree, formatted with TUI-compatible indentation and connectors.
 */
data class TreeRow(
    val node: TreeNodeDto,
    /** 0-based visual depth / column level. */
    val indent: Int,
    /** Whether to draw a connector (`├─` or `└─`) into this node. */
    val showConnector: Boolean,
    /** Whether this node is the last among its siblings at the current fork. */
    val isLast: Boolean,
    /** Vertical guide lines from ancestor branches to draw on the left. */
    val gutters: List<Gutter>,
    /** Whether this node is a foldable segment start (root or child of a fork). */
    val isFoldable: Boolean,
    /** Whether this node is currently folded. */
    val isFolded: Boolean,
    /** Whether this node lies on the active path from root to current leaf. */
    val isOnActivePath: Boolean,
    /** Whether this node is the active leaf of the session. */
    val isLeaf: Boolean,
)

/**
 * Build the tree rows from session nodes according to Pi's TUI rules.
 *
 * This is a 1:1 port of TUI's `flattenTree()` + `applyFilter()` + `recalculateVisualStructure()`:
 * 1. Single-child chains stay flat (indent does not increase on straight conversation runs).
 * 2. Multi-child branches increase indent by 1 and display connectors (`├─` / `└─`).
 * 3. The first generation after a branch indents +1 for visual grouping (`justBranched`).
 * 4. Branches containing the active leaf are prioritized at the top.
 * 5. Nodes that are filtered out are skipped and their children attach to the nearest visible ancestor.
 */
fun buildTreeRows(
    nodes: List<TreeNodeDto>,
    currentLeafId: String?,
    filter: TreeFilter = TreeFilter.Default,
    foldedIds: Set<String> = emptySet(),
): List<TreeRow> {
    if (nodes.isEmpty()) return emptyList()

    // ── 1. Index full tree & compute parent mappings ────────────────────────
    val nodeMap = nodes.associateBy { it.id }
    val parentMap = nodes.associate { it.id to it.parentId }

    // Build active path set (leaf -> root)
    val activePathIds = mutableSetOf<String>()
    if (currentLeafId != null) {
        var curr: String? = currentLeafId
        while (curr != null) {
            activePathIds.add(curr)
            curr = parentMap[curr]
        }
    }

    // Subtree contains active leaf if and only if node is on the active path
    val containsActive = activePathIds

    // Build raw children map from the flat list (preserving server order)
    val rawChildrenMap = mutableMapOf<String?, MutableList<String>>()
    for (node in nodes) {
        rawChildrenMap.getOrPut(node.parentId) { mutableListOf() }.add(node.id)
    }

    // ── 2. Full pre-order flattening prioritizing active branch ─────────────
    val flatNodeIds = mutableListOf<String>()
    val preOrderStack = ArrayDeque<String>()
    val rawRoots = rawChildrenMap[null].orEmpty()
        .sortedByDescending { it in containsActive }

    for (i in rawRoots.indices.reversed()) {
        preOrderStack.addLast(rawRoots[i])
    }

    while (preOrderStack.isNotEmpty()) {
        val id = preOrderStack.removeLast()
        flatNodeIds.add(id)
        val children = rawChildrenMap[id].orEmpty()
            .sortedByDescending { it in containsActive }
        for (i in children.indices.reversed()) {
            preOrderStack.addLast(children[i])
        }
    }

    // ── 3. Apply filter mode ────────────────────────────────────────────────
    fun passesFilter(node: TreeNodeDto): Boolean {
        val isCurrentLeaf = node.id == currentLeafId
        val isSetting = node.kind == "model" || node.kind == "thinking" || node.kind == "named"
        return when (filter) {
            TreeFilter.Default -> {
                if (isSetting) false
                else if (node.kind == "tool" && !isCurrentLeaf) false
                else true
            }
            TreeFilter.NoTools -> {
                if (isSetting) false
                else if ((node.kind == "tool" || node.kind == "toolResult") && !isCurrentLeaf) false
                else true
            }
            TreeFilter.UserOnly -> node.kind == "user"
            TreeFilter.LabeledOnly -> node.label != null
            TreeFilter.All -> true
        }
    }

    val filterPassIds = flatNodeIds.filter { id ->
        val node = nodeMap[id] ?: return@filter false
        passesFilter(node)
    }

    // Filter out descendants of folded nodes
    val skipSet = mutableSetOf<String>()
    if (foldedIds.isNotEmpty()) {
        for (id in flatNodeIds) {
            val pId = parentMap[id]
            if (pId != null && (pId in foldedIds || pId in skipSet)) {
                skipSet.add(id)
            }
        }
    }

    val visibleIdsList = filterPassIds.filter { it !in skipSet }
    if (visibleIdsList.isEmpty()) return emptyList()

    val visibleIds = visibleIdsList.toSet()

    // ── 4. Recalculate visual structure on visible tree ──────────────────────
    fun findVisibleAncestor(nodeId: String): String? {
        var curr = parentMap[nodeId]
        while (curr != null) {
            if (curr in visibleIds) return curr
            curr = parentMap[curr]
        }
        return null
    }

    val visibleParent = mutableMapOf<String, String?>()
    val visibleChildren = mutableMapOf<String?, MutableList<String>>()
    visibleChildren[null] = mutableListOf()

    for (id in visibleIdsList) {
        val ancestorId = findVisibleAncestor(id)
        visibleParent[id] = ancestorId
        visibleChildren.getOrPut(ancestorId) { mutableListOf() }.add(id)
    }

    // Track children before folding to know if a folded node is foldable
    val preFoldVisibleIds = filterPassIds.toSet()
    fun findPreFoldVisibleAncestor(nodeId: String): String? {
        var curr = parentMap[nodeId]
        while (curr != null) {
            if (curr in preFoldVisibleIds) return curr
            curr = parentMap[curr]
        }
        return null
    }
    val preFoldVisibleChildren = mutableMapOf<String?, MutableList<String>>()
    for (id in filterPassIds) {
        val ancestorId = findPreFoldVisibleAncestor(id)
        preFoldVisibleChildren.getOrPut(ancestorId) { mutableListOf() }.add(id)
    }

    fun isFoldable(nodeId: String): Boolean {
        val children = preFoldVisibleChildren[nodeId]
        if (children.isNullOrEmpty()) return false
        val parentId = visibleParent[nodeId] ?: findPreFoldVisibleAncestor(nodeId)
        if (parentId == null) return true
        val siblings = preFoldVisibleChildren[parentId]
        return siblings != null && siblings.size > 1
    }

    val visibleRootIds = visibleChildren[null].orEmpty()
    val multipleRoots = visibleRootIds.size > 1

    data class VisualState(
        val nodeId: String,
        val indent: Int,
        val justBranched: Boolean,
        val showConnector: Boolean,
        val isLast: Boolean,
        val gutters: List<Gutter>,
        val isVirtualRootChild: Boolean,
    )

    val rows = mutableListOf<TreeRow>()
    val stack = ArrayDeque<VisualState>()

    // Push visible roots in reverse order
    for (i in visibleRootIds.indices.reversed()) {
        val isLast = i == visibleRootIds.size - 1
        stack.addLast(
            VisualState(
                nodeId = visibleRootIds[i],
                indent = if (multipleRoots) 1 else 0,
                justBranched = multipleRoots,
                showConnector = multipleRoots,
                isLast = isLast,
                gutters = emptyList(),
                isVirtualRootChild = multipleRoots,
            )
        )
    }

    while (stack.isNotEmpty()) {
        val state = stack.removeLast()
        val node = nodeMap[state.nodeId] ?: continue

        val children = visibleChildren[state.nodeId].orEmpty()
        val multipleChildren = children.size > 1

        val displayIndent = if (multipleRoots) maxOf(0, state.indent - 1) else state.indent

        rows.add(
            TreeRow(
                node = node,
                indent = displayIndent,
                showConnector = state.showConnector && !state.isVirtualRootChild,
                isLast = state.isLast,
                gutters = state.gutters,
                isFoldable = isFoldable(node.id),
                isFolded = node.id in foldedIds,
                isOnActivePath = node.id in activePathIds,
                isLeaf = node.id == currentLeafId,
            )
        )

        // Calculate child indent matching TUI
        val childIndent = when {
            multipleChildren -> state.indent + 1
            state.justBranched && state.indent > 0 -> state.indent + 1
            else -> state.indent
        }

        val connectorDisplayed = state.showConnector && !state.isVirtualRootChild
        val currentDisplayIndent = if (multipleRoots) maxOf(0, state.indent - 1) else state.indent
        val connectorPosition = maxOf(0, currentDisplayIndent - 1)
        val childGutters = if (connectorDisplayed) {
            state.gutters + Gutter(position = connectorPosition, show = !state.isLast)
        } else {
            state.gutters
        }

        for (i in children.indices.reversed()) {
            val childIsLast = i == children.size - 1
            stack.addLast(
                VisualState(
                    nodeId = children[i],
                    indent = childIndent,
                    justBranched = multipleChildren,
                    showConnector = multipleChildren,
                    isLast = childIsLast,
                    gutters = childGutters,
                    isVirtualRootChild = false,
                )
            )
        }
    }

    return rows
}
