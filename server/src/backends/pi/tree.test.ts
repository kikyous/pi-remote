import assert from "node:assert/strict";
import { test } from "node:test";

import type { SessionEntry, SessionTreeNode } from "@earendil-works/pi-coding-agent";

import { buildTreeDto, describe } from "./tree.ts";

/** Session entries are structurally varied; the builders take them as they come. */
function entry(id: string, raw: Record<string, unknown>): SessionEntry {
	return { id, parentId: null, timestamp: "2026-08-12T00:00:00.000Z", ...raw } as unknown as SessionEntry;
}

function msg(id: string, message: Record<string, unknown>, ...children: SessionTreeNode[]): SessionTreeNode {
	return { entry: entry(id, { type: "message", message }), children };
}

function user(id: string, text: string, ...children: SessionTreeNode[]): SessionTreeNode {
	return msg(id, { role: "user", content: text }, ...children);
}

function assistant(id: string, text: string, ...children: SessionTreeNode[]): SessionTreeNode {
	return msg(id, { role: "assistant", content: [{ type: "text", text }] }, ...children);
}

function node(id: string, raw: Record<string, unknown>, ...children: SessionTreeNode[]): SessionTreeNode {
	return { entry: entry(id, raw), children };
}

test("a linear session is a single root chain", () => {
	const tree = buildTreeDto([user("a", "one", assistant("b", "two", user("c", "three")))]);

	assert.deepEqual(
		tree.map((n) => [n.id, n.parentId]),
		[
			["a", null],
			["b", "a"],
			["c", "b"],
		],
		"pre-order with parentIds",
	);
});

test("a fork sets parentId to the branch point", () => {
	const tree = buildTreeDto([
		user("a", "start", assistant("b", "reply", user("c", "plan A", assistant("d", "doing A")), user("e", "plan B"))),
	]);

	assert.deepEqual(
		tree.map((n) => [n.id, n.parentId]),
		[
			["a", null],
			["b", "a"],
			["c", "b"],
			["d", "c"],
			["e", "b"],
		],
	);
});

test("a label gets no row of its own and its children are promoted", () => {
	const tree = buildTreeDto([
		user("a", "start", node("l1", { type: "label", targetId: "a", label: "checkpoint" }, assistant("b", "reply"))),
	]);

	assert.deepEqual(
		tree.map((n) => [n.id, n.parentId]),
		[
			["a", null],
			["b", "a"],
		],
	);
});

test("dropping a node does not make its siblings look like a fork", () => {
	const tree = buildTreeDto([
		user("a", "start", node("c1", { type: "custom", customType: "ext", data: {} }), assistant("b", "reply")),
	]);

	assert.deepEqual(
		tree.map((n) => [n.id, n.parentId]),
		[
			["a", null],
			["b", "a"],
		],
	);
});

test("labels ride along on the node they belong to", () => {
	const tree = buildTreeDto([{ entry: entry("a", { type: "message", message: { role: "user", content: "hi" } }), children: [], label: "checkpoint-1" }]);
	assert.equal(tree[0]?.label, "checkpoint-1");
});

test("several roots stay as separate top-level nodes", () => {
	const tree = buildTreeDto([user("a", "one"), user("b", "two")]);
	assert.equal(tree.length, 2);
	assert.deepEqual(
		tree.map((n) => [n.id, n.parentId]),
		[
			["a", null],
			["b", null],
		],
	);
});

test("each entry kind gets a one-line summary", () => {
	const cases: Array<[Record<string, unknown>, string, string]> = [
		[{ type: "message", message: { role: "user", content: "hello" } }, "user", "hello"],
		[{ type: "message", message: { role: "bashExecution", command: "ls -la" } }, "bash", "ls -la"],
		[
			{ type: "message", message: { role: "toolResult", toolName: "read", content: [{ type: "text", text: "body" }] } },
			"toolResult",
			"[read]",
		],
		[{ type: "compaction", tokensBefore: 42_000 }, "compaction", "compacted 42k tokens"],
		[{ type: "branch_summary", summary: "explored A" }, "branch", "explored A"],
		[{ type: "model_change", provider: "anthropic", modelId: "claude-sonnet-4-5" }, "model", "anthropic/claude-sonnet-4-5"],
		[{ type: "thinking_level_change", thinkingLevel: "high" }, "thinking", "high"],
		[{ type: "session_info", name: "Refactor auth" }, "named", "Refactor auth"],
	];

	for (const [raw, kind, text] of cases) {
		const row = describe(entry("x", raw));
		assert.equal(row?.kind, kind, JSON.stringify(raw));
		assert.equal(row?.text, text, JSON.stringify(raw));
	}

	assert.equal(describe(entry("x", { type: "label", targetId: "a", label: "l" })), undefined);
	assert.equal(describe(entry("x", { type: "custom", customType: "ext" })), undefined);
});

test("a toolResult row carries the call signature when the call is in the same tree", () => {
	const tree = buildTreeDto([
		user(
			"a",
			"ask",
			msg(
				"b",
				{
					role: "assistant",
					content: [{ type: "toolCall", id: "c1", name: "read", arguments: { file_path: "/tmp/a.txt" } }],
				},
				msg(
					"c",
					{ role: "toolResult", toolCallId: "c1", toolName: "read", content: [{ type: "text", text: "body" }] },
					{ entry: entry("d", { type: "message", message: { role: "user", content: "follow-up" } }), children: [] },
				),
			),
		),
	]);

	assert.deepEqual(
		tree.map((n) => [n.id, n.kind, n.text, n.parentId]),
		[
			["a", "user", "ask", null],
			["b", "tool", "read(/tmp/a.txt)", "a"],
			["c", "toolResult", "[read: /tmp/a.txt]", "b"],
			["d", "user", "follow-up", "c"],
		],
	);
});

test("a tool-only assistant turn is named after its calls", () => {
	const row = describe(
		entry("x", {
			type: "message",
			message: {
				role: "assistant",
				content: [
					{ type: "toolCall", id: "c1", name: "read", arguments: { file_path: "/tmp/a.txt" } },
					{ type: "toolCall", id: "c2", name: "bash", arguments: { command: "ls" } },
				],
			},
		}),
	);

	assert.equal(row?.kind, "tool", "so the client can filter it out as tool noise");
	assert.equal(row?.text, "read(/tmp/a.txt), bash(ls)");
});

test("an assistant turn with nothing to show says why", () => {
	const aborted = describe(entry("x", { type: "message", message: { role: "assistant", content: [], stopReason: "aborted" } }));
	assert.equal(aborted?.text, "(aborted)");

	const failed = describe(
		entry("x", { type: "message", message: { role: "assistant", content: [], stopReason: "error", errorMessage: "overloaded" } }),
	);
	assert.equal(failed?.text, "overloaded");

	const empty = describe(entry("x", { type: "message", message: { role: "assistant", content: [] } }));
	assert.equal(empty?.text, "(no content)");
});

test("a summary is one line, cut by characters rather than bytes", () => {
	const row = describe(entry("x", { type: "message", message: { role: "user", content: `line one\n\n  line   two` } }));
	assert.equal(row?.text, "line one line two", "newlines and runs of spaces collapse");

	const long = describe(entry("x", { type: "message", message: { role: "user", content: "中".repeat(300) } }));
	// 100 characters plus the ellipsis — not 100 bytes, which would cut a
	// multi-byte character in half.
	assert.equal(Array.from(long?.text ?? "").length, 101);
	assert.equal(long?.text.endsWith("…"), true);
});
