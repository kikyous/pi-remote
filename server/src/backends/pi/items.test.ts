import assert from "node:assert/strict";
import { test } from "node:test";

import type { SessionEntry } from "@earendil-works/pi-coding-agent";

import { itemsFromEntries, MAX_TEXT_BYTES } from "./items.ts";
import type { Item } from "../../protocol.ts";
import { parseRef } from "../../refs.ts";

/** Session entries are structurally varied; the builder takes them as they come. */
function entries(...raw: unknown[]): SessionEntry[] {
	return raw as SessionEntry[];
}

function message(id: string, msg: Record<string, unknown>): Record<string, unknown> {
	return { type: "message", id, parentId: null, timestamp: "2026-08-12T00:00:00.000Z", message: msg };
}

test("a user message becomes one item", () => {
	const items = itemsFromEntries(entries(message("e1", { role: "user", content: "hello there" })));
	assert.equal(items.length, 1);
	assert.equal(items[0]?.kind, "user");
	assert.equal((items[0] as Extract<Item, { kind: "user" }>).text.s, "hello there");
});

test("a tool call and its result become one row", () => {
	const items = itemsFromEntries(
		entries(
			message("e1", {
				role: "assistant",
				content: [
					{ type: "text", text: "let me look" },
					{ type: "toolCall", id: "call-1", name: "read", arguments: { file_path: "/tmp/a.txt" } },
				],
			}),
			message("e2", { role: "toolResult", toolCallId: "call-1", toolName: "read", content: [{ type: "text", text: "file body" }] }),
		),
	);

	assert.deepEqual(items.map((i) => i.kind), ["assistant", "tool"], "the result does not get a row of its own");
	const tool = items[1] as Extract<Item, { kind: "tool" }>;
	assert.equal(tool.name, "read");
	// The path, not whichever argument happens to come first.
	assert.equal(tool.title, "/tmp/a.txt");
	assert.equal(tool.output.s, "file body");
	assert.equal(tool.running, undefined, "it finished");
	// Its id comes from the call site, so it is stable from the moment the call
	// appears rather than changing when the output lands.
	assert.equal(tool.id, "e1#1");
});

test("a call still waiting on its result is marked running", () => {
	const items = itemsFromEntries(
		entries(
			message("e1", {
				role: "assistant",
				content: [{ type: "toolCall", id: "call-1", name: "bash", arguments: { command: "sleep 10" } }],
			}),
		),
	);

	const tool = items[1] as Extract<Item, { kind: "tool" }>;
	assert.equal(tool.running, true);
	assert.equal(tool.output.s, "");
	assert.equal(tool.title, "sleep 10");
});

test("a result whose call is not on this page is shown standalone", () => {
	// This is what a page boundary between a call and its result looks like. The
	// output must still be visible rather than dropped on the floor.
	const items = itemsFromEntries(
		entries(message("e2", { role: "toolResult", toolCallId: "call-from-earlier", toolName: "grep", content: [{ type: "text", text: "3 matches" }] })),
	);

	assert.equal(items.length, 1);
	const tool = items[0] as Extract<Item, { kind: "tool" }>;
	assert.equal(tool.kind, "tool");
	assert.equal(tool.output.s, "3 matches");
	assert.equal(tool.callId, "call-from-earlier");
});

test("an errored result and an image result are flagged", () => {
	const items = itemsFromEntries(
		entries(
			message("e1", { role: "assistant", content: [{ type: "toolCall", id: "c1", name: "read", arguments: { path: "/x.png" } }] }),
			message("e2", {
				role: "toolResult",
				toolCallId: "c1",
				toolName: "read",
				isError: true,
				content: [
					{ type: "text", text: "could not decode" },
					{ type: "image", data: "BASE64" },
				],
			}),
		),
	);

	const tool = items[1] as Extract<Item, { kind: "tool" }>;
	assert.equal(tool.isError, true);
	assert.equal(tool.hasImage, true);
});

test("an edit call carries a parsed diff whatever the tool is named", () => {
	const items = itemsFromEntries(
		entries(
			message("e1", {
				role: "assistant",
				content: [
					{
						type: "toolCall",
						id: "c1",
						// Deliberately not called "edit": recognition is by shape.
						name: "apply_patch",
						arguments: { path: "/a.ts", edits: [{ oldText: "before", newText: "after" }] },
					},
				],
			}),
		),
	);

	const tool = items[1] as Extract<Item, { kind: "tool" }>;
	assert.deepEqual(tool.diff, { path: "/a.ts", hunks: [{ old: "before", new: "after" }] });
});

test("a bash execution becomes a tool row with its exit code", () => {
	const items = itemsFromEntries(entries(message("e1", { role: "bashExecution", command: "ls -la", output: "total 0\n", exitCode: 0 })));

	const tool = items[0] as Extract<Item, { kind: "tool" }>;
	assert.equal(tool.name, "bash");
	assert.equal(tool.title, "ls -la");
	assert.equal(tool.output.s, "total 0\n");
	assert.equal(tool.exit, 0);
});

test("oversized text is cut and carries a handle for the rest", () => {
	const long = "x".repeat(MAX_TEXT_BYTES + 500);
	const items = itemsFromEntries(entries(message("e1", { role: "assistant", content: [{ type: "text", text: long }] })));

	const assistant = items[0] as Extract<Item, { kind: "assistant" }>;
	assert.equal(assistant.text.s.length, MAX_TEXT_BYTES);
	assert.equal(assistant.text.more?.bytes, MAX_TEXT_BYTES + 500);
	// The handle resolves back to the exact block it came from.
	assert.deepEqual(parseRef(assistant.text.more!.ref), { entryId: "e1", part: "text", index: 0 });
});

test("a multi-byte character is never split", () => {
	// Three bytes each, so the budget lands mid-character.
	const long = "汉".repeat(MAX_TEXT_BYTES);
	const items = itemsFromEntries(entries(message("e1", { role: "assistant", content: [{ type: "text", text: long }] })));

	const assistant = items[0] as Extract<Item, { kind: "assistant" }>;
	assert.equal(assistant.text.s.includes("�"), false, "no replacement character at the cut");
	assert.ok(Buffer.byteLength(assistant.text.s) <= MAX_TEXT_BYTES);
});

test("thinking travels as a teaser, because the UI collapses it", () => {
	const long = "reasoning ".repeat(100);
	const items = itemsFromEntries(
		entries(message("e1", { role: "assistant", content: [{ type: "thinking", thinking: long }, { type: "text", text: "answer" }] })),
	);

	const assistant = items[0] as Extract<Item, { kind: "assistant" }>;
	assert.equal(assistant.thinking?.s.length, 200);
	assert.deepEqual(parseRef(assistant.thinking!.more!.ref), { entryId: "e1", part: "thinking", index: 0 });
	assert.equal(assistant.text.s, "answer", "the text is untouched by the thinking cut");
});

test("an image is a placeholder, never bytes", () => {
	const items = itemsFromEntries(
		entries(message("e1", { role: "user", content: [{ type: "text", text: "look" }, { type: "image", data: "A".repeat(1000), mimeType: "image/png" }] })),
	);

	const user = items[0] as Extract<Item, { kind: "user" }>;
	assert.equal(user.images?.length, 1);
	assert.equal(user.images?.[0]?.mime, "image/png");
	assert.equal(user.images?.[0]?.bytes, 1000);
	// The whole point: the base64 does not appear anywhere in the item.
	assert.equal(JSON.stringify(user).includes("AAAA"), false);
});

test("an all-zero usage is dropped, because a failed turn is not worth a line", () => {
	const zero = { input: 0, output: 0, cacheRead: 0, cost: { total: 0 } };
	const real = { input: 10, output: 5, cacheRead: 0, cost: { total: 0.001 } };

	const [failed] = itemsFromEntries(entries(message("e1", { role: "assistant", content: [], usage: zero })));
	assert.equal((failed as Extract<Item, { kind: "assistant" }>).usage, undefined);

	const [ok] = itemsFromEntries(entries(message("e2", { role: "assistant", content: [], usage: real })));
	assert.deepEqual((ok as Extract<Item, { kind: "assistant" }>).usage, { in: 10, out: 5, cacheRead: 0, cost: 0.001 });
});

test("structural entries become notices, and unknown kinds are skipped", () => {
	const items = itemsFromEntries(
		entries(
			{ type: "model_change", id: "e1", timestamp: "t", provider: "anthropic", modelId: "claude-opus-5" },
			{ type: "thinking_level_change", id: "e2", timestamp: "t", thinkingLevel: "high" },
			{ type: "compaction", id: "e3", timestamp: "t", summary: "..." },
			{ type: "session_info", id: "e4", timestamp: "t", name: "My session" },
			{ type: "label", id: "e5", timestamp: "t" },
			{ type: "something-pi-added-later", id: "e6", timestamp: "t" },
		),
	);

	assert.deepEqual(
		items.map((i) => [i.kind, (i as Extract<Item, { kind: "notice" }>).note, (i as Extract<Item, { kind: "notice" }>).arg]),
		[
			["notice", "model", "anthropic/claude-opus-5"],
			["notice", "thinking", "high"],
			["notice", "compaction", undefined],
			["notice", "named", "My session"],
		],
		"label and unknown kinds carry no conversation content",
	);
});

test("an unnamed session_info produces nothing", () => {
	assert.deepEqual(itemsFromEntries(entries({ type: "session_info", id: "e1", timestamp: "t" })), []);
});
