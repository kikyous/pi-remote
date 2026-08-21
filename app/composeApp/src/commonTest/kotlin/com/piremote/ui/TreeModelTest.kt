package com.piremote.ui

import com.piremote.net.TreeNodeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun n(
    id: String,
    parentId: String?,
    kind: String,
    text: String = "$kind $id",
    label: String? = null,
) = TreeNodeDto(
    id = id,
    parentId = parentId,
    kind = kind,
    text = text,
    at = "2026-08-12T00:00:00.000Z",
    label = label,
)

class TreeModelTest {

    @Test
    fun `a linear session stays flat at indent 0`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("u2", "a1", "user"),
            n("a2", "u2", "assistant"),
            n("u3", "a2", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "u3", filter = TreeFilter.Default)

        assertEquals(listOf("u1", "a1", "u2", "a2", "u3"), rows.map { it.node.id })
        assertTrue(rows.all { it.indent == 0 }, "all rows in a straight line remain at indent 0")
        assertTrue(rows.none { it.showConnector }, "no branch connectors in a single chain")
    }

    @Test
    fun `a fork increases indent by 1 and shows connectors`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("x1", "a1", "user"),
            n("y1", "a1", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "x1", filter = TreeFilter.Default)

        assertEquals(listOf("u1", "a1", "x1", "y1"), rows.map { it.node.id })
        val x1 = rows[2]
        val y1 = rows[3]

        assertEquals(1, x1.indent)
        assertEquals(1, y1.indent)
        assertTrue(x1.showConnector)
        assertTrue(x1.isFirst)
        assertFalse(x1.isLast)
        assertTrue(y1.showConnector)
        assertFalse(y1.isFirst)
        assertTrue(y1.isLast)
    }

    @Test
    fun `active branch is prioritized first in ordering`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("x1", "a1", "user"),
            n("x2", "x1", "assistant"),
            n("y1", "a1", "user"),
            n("y2", "y1", "assistant"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "y2", filter = TreeFilter.Default)

        assertEquals(listOf("u1", "a1", "y1", "y2", "x1", "x2"), rows.map { it.node.id })
        assertTrue(rows.first { it.node.id == "y1" }.isOnActivePath)
        assertTrue(rows.first { it.node.id == "y2" }.isLeaf)
        assertFalse(rows.first { it.node.id == "x1" }.isOnActivePath)
    }

    @Test
    fun `first generation after a branch gets visual grouping indent`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("x1", "a1", "user"),
            n("x2", "x1", "assistant"),
            n("x3", "x2", "user"),
            n("y1", "a1", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "x3", filter = TreeFilter.Default)

        val x1 = rows.first { it.node.id == "x1" }
        val x2 = rows.first { it.node.id == "x2" }
        val x3 = rows.first { it.node.id == "x3" }

        assertEquals(1, x1.indent)
        assertEquals(2, x2.indent, "x2 is first generation after fork, indent +1")
        assertEquals(2, x3.indent, "x3 continues x2 single-chain, stays flat at 2")
    }

    @Test
    fun `Default filter hides settings and pure tool assistant turns`() {
        val nodes = listOf(
            n("m1", null, "model"),
            n("k1", "m1", "thinking"),
            n("u1", "k1", "user"),
            n("a1", "u1", "assistant"),
            n("t1", "a1", "tool"),
            n("u2", "t1", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "u2", filter = TreeFilter.Default)

        assertEquals(listOf("u1", "a1", "u2"), rows.map { it.node.id })
    }

    @Test
    fun `NoTools filter hides both tool calls and tool results`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("t1", "a1", "tool"),
            n("tr1", "t1", "toolResult"),
            n("u2", "tr1", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "u2", filter = TreeFilter.NoTools)

        assertEquals(listOf("u1", "a1", "u2"), rows.map { it.node.id })
    }

    @Test
    fun `UserOnly filter keeps only user nodes`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("u2", "a1", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "u2", filter = TreeFilter.UserOnly)

        assertEquals(listOf("u1", "u2"), rows.map { it.node.id })
    }

    @Test
    fun `folding a node hides all its descendants`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("x1", "a1", "user"),
            n("x2", "x1", "assistant"),
            n("y1", "a1", "user"),
            n("y2", "y1", "assistant"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "y2", filter = TreeFilter.Default, foldedIds = setOf("x1"))

        assertEquals(listOf("u1", "a1", "y1", "y2", "x1"), rows.map { it.node.id })
        val x1 = rows.first { it.node.id == "x1" }
        assertTrue(x1.isFolded)
        assertFalse(rows.any { it.node.id == "x2" }, "x2 descendant is hidden when x1 is folded")
    }

    @Test
    fun `gutters propagate to descendants of non-last siblings`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("x1", "a1", "user"),
            n("x2", "x1", "assistant"),
            n("y1", "a1", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "x2", filter = TreeFilter.Default)

        val x2 = rows.first { it.node.id == "x2" }
        assertTrue(x2.gutters.any { it.position == 0 && it.show }, "x2 inherits vertical gutter line from x1")
    }

    @Test
    fun `isFoldable is true only for roots and fork children`() {
        val nodes = listOf(
            n("u1", null, "user"),
            n("a1", "u1", "assistant"),
            n("x1", "a1", "user"),
            n("y1", "a1", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "y1", filter = TreeFilter.Default)

        assertTrue(rows.first { it.node.id == "u1" }.isFoldable, "root is foldable")
        assertFalse(rows.first { it.node.id == "a1" }.isFoldable, "single child is not foldable")
        assertFalse(rows.first { it.node.id == "y1" }.isFoldable, "leaf is not foldable")
    }

    @Test
    fun `filtered out node promotes its children`() {
        val nodes = listOf(
            n("m1", null, "model"),
            n("u1", "m1", "user"),
        )

        val rows = buildTreeRows(nodes, currentLeafId = "u1", filter = TreeFilter.Default)

        assertEquals(listOf("u1"), rows.map { it.node.id })
    }

    @Test
    fun `an empty tree has no rows`() {
        assertTrue(buildTreeRows(emptyList(), currentLeafId = null).isEmpty())
    }
}
