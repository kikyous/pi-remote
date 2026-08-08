import assert from "node:assert/strict";
import { test } from "node:test";

import { extractFullPart, slimEntry } from "./slim.ts";

const BIG = "x".repeat(20_000);

function messageEntry(message: unknown): Record<string, unknown> {
	return { type: "message", id: "abc12345", parentId: null, timestamp: "2026-01-01T00:00:00.000Z", message };
}

function blocks(entry: unknown): Record<string, unknown>[] {
	const message = (entry as { message: { content: unknown[] } }).message;
	return message.content as Record<string, unknown>[];
}

test("strips image payloads, the single largest item in real sessions", () => {
	const entry = messageEntry({
		role: "toolResult",
		toolName: "read",
		content: [
			{ type: "text", text: "here is the file" },
			{ type: "image", data: "A".repeat(361_129), mimeType: "image/png" },
		],
	});

	const [text, image] = blocks(slimEntry(entry));

	assert.equal(text!.text, "here is the file", "small text is left alone");
	assert.equal(image!.data, "", "base64 payload is dropped");
	assert.equal(image!.mimeType, "image/png", "mime type survives for the placeholder");
	assert.equal(image!.truncated, true);
	assert.equal(image!.fullLength, 361_129);
	assert.equal(image!.part, "image");
	assert.equal(image!.index, 1);
});

test("strips images nested under `source` as well", () => {
	const entry = messageEntry({
		role: "user",
		content: [{ type: "image", source: { type: "base64", media_type: "image/jpeg", data: "B".repeat(5000) } }],
	});

	const [image] = blocks(slimEntry(entry));

	assert.equal((image!.source as Record<string, unknown>).data, "");
	assert.equal(image!.fullLength, 5000);
});

test("truncates oversized tool result text and records the original size", () => {
	const entry = messageEntry({ role: "toolResult", toolName: "read", content: [{ type: "text", text: BIG }] });

	const [text] = blocks(slimEntry(entry));

	assert.equal(text!.truncated, true);
	assert.equal(text!.fullLength, 20_000);
	assert.equal((text!.text as string).length, 8 * 1024);
});

test("leaves text under the threshold untouched and unmarked", () => {
	const entry = messageEntry({ role: "toolResult", content: [{ type: "text", text: "short" }] });

	const [text] = blocks(slimEntry(entry));

	assert.equal(text!.text, "short");
	assert.equal(text!.truncated, undefined, "no truncation marker on small content");
});

test("truncates tool call arguments field by field, keeping short ones intact", () => {
	// `write` puts the whole file in `content` but the UI needs `file_path`
	// next to it to render the tool card without a round trip.
	const entry = messageEntry({
		role: "assistant",
		content: [{ type: "toolCall", id: "call_1", name: "write", arguments: { file_path: "/src/foo.ts", content: BIG } }],
	});

	const [call] = blocks(slimEntry(entry));
	const args = call!.arguments as Record<string, string>;

	assert.equal(args.file_path, "/src/foo.ts", "short argument survives verbatim");
	assert.equal(args.content!.length, 8 * 1024, "long argument is cut");
	assert.equal(call!.truncated, true);
	assert.equal(call!.part, "arguments");
});

test("keeps a thinking preview rather than the whole block", () => {
	const entry = messageEntry({
		role: "assistant",
		content: [{ type: "thinking", thinking: "y".repeat(12_249) }],
	});

	const [thinking] = blocks(slimEntry(entry));

	assert.equal((thinking!.thinking as string).length, 200);
	assert.equal(thinking!.truncated, true);
	assert.equal(thinking!.fullLength, 12_249);
});

test("truncates bash execution output", () => {
	const entry = messageEntry({ role: "bashExecution", command: "ls -R", output: BIG, exitCode: 0 });

	const message = (slimEntry(entry) as { message: Record<string, unknown> }).message;

	assert.equal((message.output as string).length, 8 * 1024);
	assert.equal(message.truncated, true);
	assert.equal(message.part, "output");
});

test("shrinks the materialized tail carried by a compaction entry", () => {
	const entry = {
		type: "compaction",
		id: "cmp00001",
		parentId: "abc12345",
		summary: BIG,
		tokensBefore: 150_000,
		retainedTail: [{ role: "toolResult", content: [{ type: "text", text: BIG }] }],
	};

	const slim = slimEntry(entry) as Record<string, unknown>;
	const tail = slim.retainedTail as { content: Record<string, unknown>[] }[];

	assert.equal((slim.summary as string).length, 8 * 1024);
	assert.equal((tail[0]!.content[0]!.text as string).length, 8 * 1024);
});

test("does not mutate the input, which SessionManager keeps cached", () => {
	// If slimming wrote through to the SDK's own entry objects, the cache would
	// be poisoned and `/full` could never recover the original.
	const original = messageEntry({
		role: "toolResult",
		content: [{ type: "image", data: "A".repeat(1000), mimeType: "image/png" }],
	});
	const snapshot = JSON.stringify(original);

	slimEntry(original);

	assert.equal(JSON.stringify(original), snapshot);
});

test("cuts on a character boundary for multi-byte text", () => {
	const entry = messageEntry({ role: "toolResult", content: [{ type: "text", text: "汉".repeat(5000) }] });

	const [text] = blocks(slimEntry(entry));

	assert.ok(!(text!.text as string).includes("�"), "no replacement character at the cut");
	assert.ok(Buffer.byteLength(text!.text as string) <= 8 * 1024);
});

test("passes through structural entries unchanged", () => {
	for (const entry of [
		{ type: "model_change", id: "m0000001", parentId: null, provider: "anthropic", modelId: "claude-sonnet-4-5" },
		{ type: "thinking_level_change", id: "t0000001", parentId: "m0000001", thinkingLevel: "high" },
		{ type: "session_info", id: "s0000001", parentId: null, name: "my work" },
	]) {
		assert.deepEqual(slimEntry(entry), entry);
	}
});

test("extractFullPart recovers each shrunk part from the original entry", () => {
	const thinking = "y".repeat(12_249);
	const entry = messageEntry({
		role: "assistant",
		content: [
			{ type: "thinking", thinking },
			{ type: "toolCall", id: "c1", name: "write", arguments: { file_path: "/a.ts", content: BIG } },
		],
	});

	assert.equal(extractFullPart(entry, "thinking", 0), thinking);
	assert.equal(JSON.parse(extractFullPart(entry, "arguments", 1)!).content, BIG);
	assert.equal(extractFullPart(entry, "text", 0), undefined, "wrong part at a valid index yields nothing");
	assert.equal(extractFullPart(entry, "thinking", 99), undefined, "out-of-range index yields nothing");
});

test("extractFullPart reads bash output without an index", () => {
	const entry = messageEntry({ role: "bashExecution", command: "ls", output: BIG });

	assert.equal(extractFullPart(entry, "output", undefined), BIG);
});
