import assert from "node:assert/strict";
import { test } from "node:test";

import type { SessionEntry } from "@earendil-works/pi-coding-agent";

import { totalsOf } from "./stats.ts";

/** Session entries are structurally varied; the counter takes them as they come. */
function entries(...raw: unknown[]): SessionEntry[] {
	return raw as SessionEntry[];
}

function message(id: string, msg: Record<string, unknown>): Record<string, unknown> {
	return { type: "message", id, parentId: null, timestamp: "2026-08-15T00:00:00.000Z", message: msg };
}

function usage(input: number, output: number, cacheRead = 0, cacheWrite = 0, cost = 0): Record<string, unknown> {
	return { input, output, cacheRead, cacheWrite, cost: { total: cost } };
}

test("counts each role, and tool calls inside the message that made them", () => {
	const totals = totalsOf(
		entries(
			message("e1", { role: "user", content: "go" }),
			message("e2", {
				role: "assistant",
				content: [
					{ type: "text", text: "on it" },
					{ type: "toolCall", id: "c1", name: "read" },
					{ type: "toolCall", id: "c2", name: "bash" },
				],
				usage: usage(10, 5),
			}),
			message("e3", { role: "toolResult", toolCallId: "c1", content: [] }),
			message("e4", { role: "toolResult", toolCallId: "c2", content: [] }),
		),
	);

	assert.deepEqual(totals.messages, { user: 1, assistant: 1, toolCalls: 2, toolResults: 2, total: 4 });
});

test("adds up tokens and cost across assistant, tool result and compaction entries", () => {
	const totals = totalsOf(
		entries(
			message("e1", { role: "assistant", content: [], usage: usage(100, 20, 3_000, 40, 0.01) }),
			// A tool that ran a model of its own reports usage on its result.
			message("e2", { role: "toolResult", toolCallId: "c1", content: [], usage: usage(7, 3, 0, 0, 0.002) }),
			// The summary itself cost a model call; pi records it on the entry.
			{ type: "compaction", id: "e3", parentId: null, timestamp: "", summary: "…", firstKeptEntryId: "e1", tokensBefore: 900, usage: usage(50, 10, 0, 0, 0.003) },
		),
	);

	assert.deepEqual(totals.tokens, { input: 157, output: 33, cacheRead: 3_000, cacheWrite: 40, total: 3_230 });
	assert.equal(Number(totals.cost.toFixed(3)), 0.015);
});

test("entries with no usage, and non-message entries, add nothing", () => {
	const totals = totalsOf(
		entries(
			// An aborted turn: pi writes the message with no usage at all.
			message("e1", { role: "assistant", content: [] }),
			{ type: "model_change", id: "e2", parentId: null, timestamp: "", provider: "anthropic", modelId: "claude" },
			{ type: "session_info", id: "e3", parentId: null, timestamp: "", name: "renamed" },
		),
	);

	assert.deepEqual(totals.tokens, { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 });
	assert.equal(totals.cost, 0);
	assert.deepEqual(totals.messages, { user: 0, assistant: 1, toolCalls: 0, toolResults: 0, total: 1 });
});

test("an empty session totals to zero rather than failing", () => {
	const totals = totalsOf([]);
	assert.equal(totals.tokens.total, 0);
	assert.equal(totals.messages.total, 0);
	assert.equal(totals.cost, 0);
});
