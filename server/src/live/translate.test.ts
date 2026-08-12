import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";

import type { Item, SessionStatus, Text, TextPatch } from "../protocol.ts";
import { createCoalescer } from "./coalesce.ts";
import { createTranslator, type Mutation } from "./translate.ts";

/**
 * A real captured turn — "bash, print 1 to 5 with 1s between" — replayed through
 * the translator.
 *
 * The capture is the regression baseline for the whole point of protocol v2: on
 * the old wire this turn cost 19,930 bytes across 135 frames, of which 51% of the
 * bytes were events the client never read.
 */
const TRACE = join(import.meta.dirname, "..", "..", "test", "fixtures", "bash-turn.jsonl");

/** Mirrors the client: fold a push stream into the item list it describes. */
function applyMutations(mutations: Mutation[]): { items: Item[]; status: SessionStatus } {
	const items: Item[] = [];
	let status: SessionStatus = { running: false, queued: [], compacting: false };

	for (const m of mutations) {
		if (m.t === "status") {
			status = m.status;
			continue;
		}
		if (m.t === "add") {
			items.push(m.item);
			continue;
		}
		const at = items.findIndex((item) => item.id === m.id);
		assert.notEqual(at, -1, `patch targets a known item (${m.id})`);
		const target = { ...items[at]! } as Record<string, unknown>;
		if (m.append) {
			const field = m.append.f;
			const current = target[field] as Text | undefined;
			target[field] = { ...current, s: (current?.s ?? "") + m.append.s };
		}
		for (const [key, value] of Object.entries(m.set ?? {})) {
			// A text field is merged, not replaced: a patch carrying only `more`
			// means "keep your text, take the handle".
			if ((key === "text" || key === "thinking" || key === "output") && value) {
				const patch = value as TextPatch;
				const current = target[key] as Text | undefined;
				target[key] = {
					s: patch.s ?? current?.s ?? "",
					...(patch.more ?? current?.more ? { more: patch.more ?? current?.more } : {}),
				};
			} else {
				target[key] = value;
			}
		}
		items[at] = target as unknown as Item;
	}
	return { items, status };
}

/**
 * Feed the capture through translate + coalesce.
 *
 * `entry_appended` is synthesized after each `message_end`, exactly as the pool
 * does: the SDK does not emit one for a normal turn, and the persisted entry is
 * what carries usage, refs and truncation markers.
 */
function replayTrace(): { mutations: Mutation[]; frames: string[] } {
	const events = readFileSync(TRACE, "utf8")
		.split("\n")
		.filter((line) => line.trim().length > 0)
		.map((line) => JSON.parse(line) as Record<string, unknown>);

	const mutations: Mutation[] = [];
	const frames: string[] = [];
	const coalescer = createCoalescer((m) => {
		mutations.push(m);
		frames.push(JSON.stringify(m));
	});

	let running = false;
	const translator = createTranslator((m) => coalescer.push(m), () => ({ running }));

	let entryId = 0;
	for (const event of events) {
		if (event.type === "session") continue;
		if (event.type === "agent_start") running = true;
		if (event.type === "agent_settled") running = false;

		translator.handle(event as unknown as AgentSessionEvent);

		if (event.type === "message_end") {
			entryId += 1;
			translator.handle({
				type: "entry_appended",
				entry: {
					type: "message",
					id: `entry-${entryId}`,
					parentId: null,
					timestamp: "2026-08-12T10:27:13.000Z",
					message: event.message,
				},
			} as unknown as AgentSessionEvent);
		}
	}
	coalescer.flush();
	return { mutations, frames };
}

test("the captured turn becomes the four items it should", () => {
	const { items } = applyMutations(replayTrace().mutations);

	assert.deepEqual(
		items.map((i) => i.kind),
		["user", "assistant", "tool", "assistant"],
		"a question, the thinking that called bash, the bash row, then the answer",
	);

	const [user, first, tool, answer] = items as [
		Extract<Item, { kind: "user" }>,
		Extract<Item, { kind: "assistant" }>,
		Extract<Item, { kind: "tool" }>,
		Extract<Item, { kind: "assistant" }>,
	];

	assert.equal(user.text.s, "bash 输出1到5, 中间间隔1s");

	// The first assistant message reasoned and called a tool; it produced no text.
	assert.ok(first.thinking && first.thinking.s.length > 0, "thinking was streamed");
	assert.equal(first.text.s, "");
	assert.equal(first.pending, false, "settled once its entry landed");

	assert.equal(tool.name, "bash");
	assert.ok(tool.title && tool.title.includes("echo"), `the command is the row's title: ${tool.title}`);
	assert.match(tool.output.s, /1\n2\n3\n4\n5/, "the bash output arrived");
	assert.equal(tool.running, false, "no longer executing");

	assert.ok(answer.text.s.length > 0, "the final answer has text");
	assert.equal(answer.pending, false);
});

test("the streamed text matches the stored text, so it is never resent", () => {
	const { mutations } = replayTrace();

	// Every `set` that carries text at all is drift — the normal path leaves the
	// client's streamed copy alone, because it is byte-identical.
	const resends = mutations.filter((m) => m.t === "patch" && (m.set?.text !== undefined || m.set?.thinking !== undefined));
	assert.deepEqual(resends, [], "no text crossed the wire twice");
});

test("the turn fits in a fraction of the old wire", () => {
	const { frames } = replayTrace();
	const bytes = frames.reduce((sum, f) => sum + Buffer.byteLength(f), 0);

	// Old wire: 19,930 bytes / 135 frames, plus four synthesized entry_appended
	// events on top. Frames here are a lower bound — a real 40-second stream
	// flushes on the 80ms timer rather than once at the end — but the byte count
	// is the honest comparison, and it is what a phone's radio pays for.
	assert.ok(frames.length <= 20, `frames: ${frames.length}`);
	assert.ok(bytes <= 3 * 1024, `bytes: ${bytes}`);

	// None of the SDK's internal chatter survives the boundary.
	const joined = frames.join("");
	for (const leaked of ["toolcall_delta", "assistantMessageEvent", "turn_end", "agent_end", "message_start"]) {
		assert.equal(joined.includes(leaked), false, `${leaked} does not reach the client`);
	}
});

test("status follows the run rather than lifecycle events", () => {
	const { mutations } = replayTrace();
	const statuses = mutations.filter((m): m is Extract<Mutation, { t: "status" }> => m.t === "status");

	assert.ok(statuses.length >= 2, "at least on and off");
	assert.equal(statuses[0]?.status.running, true);
	assert.equal(statuses[statuses.length - 1]?.status.running, false);
	// Consecutive duplicates would be pure noise on the wire.
	for (let i = 1; i < statuses.length; i++) {
		assert.notEqual(statuses[i]!.status.running, statuses[i - 1]!.status.running, "no repeated status");
	}
});

test("a cumulative tool output is sent as appends, not resent whole", () => {
	const mutations: Mutation[] = [];
	const coalescer = createCoalescer((m) => mutations.push(m));
	const translator = createTranslator((m) => coalescer.push(m), () => ({ running: true }));

	// The call has to exist before its output can be patched.
	translator.handle({
		type: "entry_appended",
		entry: {
			type: "message",
			id: "e1",
			parentId: null,
			timestamp: "2026-08-12T10:00:00.000Z",
			message: {
				role: "assistant",
				content: [{ type: "toolCall", id: "call-1", name: "bash", arguments: { command: "seq 3" } }],
			},
		},
	} as unknown as AgentSessionEvent);

	// pi resends the whole output so far on every update.
	for (const soFar of ["1\n", "1\n2\n", "1\n2\n3\n"]) {
		translator.handle({
			type: "tool_execution_update",
			toolCallId: "call-1",
			toolName: "bash",
			partialResult: { content: [{ type: "text", text: soFar }] },
		} as unknown as AgentSessionEvent);
	}
	coalescer.flush();

	const appends = mutations.filter((m) => m.t === "patch" && m.append?.f === "output");
	assert.equal(appends.length, 1, "three updates merged into one frame");
	assert.equal(appends[0]?.t === "patch" ? appends[0].append?.s : undefined, "1\n2\n3\n", "each byte sent once");
});

test("a tool that rewrites its output replaces instead of appending", () => {
	const mutations: Mutation[] = [];
	const coalescer = createCoalescer((m) => mutations.push(m));
	const translator = createTranslator((m) => coalescer.push(m), () => ({ running: true }));

	translator.handle({
		type: "entry_appended",
		entry: {
			type: "message",
			id: "e1",
			parentId: null,
			timestamp: "2026-08-12T10:00:00.000Z",
			message: {
				role: "assistant",
				content: [{ type: "toolCall", id: "call-1", name: "spinner", arguments: {} }],
			},
		},
	} as unknown as AgentSessionEvent);

	for (const soFar of ["working...", "done"]) {
		translator.handle({
			type: "tool_execution_update",
			toolCallId: "call-1",
			toolName: "spinner",
			partialResult: { content: [{ type: "text", text: soFar }] },
		} as unknown as AgentSessionEvent);
	}
	coalescer.flush();

	// "done" is shorter than "working...", so it cannot be an append.
	const last = mutations[mutations.length - 1];
	assert.equal(last?.t === "patch" ? last.set?.output?.s : undefined, "done");
});
