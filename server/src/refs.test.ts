import assert from "node:assert/strict";
import { test } from "node:test";

import { extractFullPart, makeRef, parseRef } from "./refs.ts";

test("a ref survives a round trip, with and without an index", () => {
	for (const [entryId, part, index] of [
		["01JABC", "text", 0],
		["01JABC", "thinking", 3],
		["01JABC", "output", undefined],
		["01JABC", "arguments", 12],
	] as const) {
		const parsed = parseRef(makeRef(entryId, part, index));
		assert.deepEqual(parsed, { entryId, part, index });
	}
});

test("a malformed ref is rejected rather than guessed at", () => {
	for (const bad of ["", "only-one-part", "a|b", "a|nosuchpart|0", "|text|0", "a|text|-1", "a|text|x", "a|text|0|extra"]) {
		assert.equal(parseRef(bad), undefined, bad);
	}
});

test("extractFullPart reads each kind of shortened content", () => {
	const entry = {
		type: "message",
		id: "e1",
		message: {
			role: "assistant",
			content: [
				{ type: "text", text: "the whole answer" },
				{ type: "thinking", thinking: "the whole reasoning" },
				{ type: "toolCall", arguments: { path: "/x", content: "file body" } },
				{ type: "image", data: "BASE64" },
				{ type: "image", source: { data: "NESTED64" } },
			],
		},
	};

	assert.equal(extractFullPart(entry, "text", 0), "the whole answer");
	assert.equal(extractFullPart(entry, "thinking", 1), "the whole reasoning");
	assert.equal(extractFullPart(entry, "arguments", 2), JSON.stringify({ path: "/x", content: "file body" }));
	assert.equal(extractFullPart(entry, "image", 3), "BASE64");
	// pi nests an image under `source` on some paths; both shapes resolve.
	assert.equal(extractFullPart(entry, "image", 4), "NESTED64");
});

test("a bash execution's output needs no index", () => {
	const entry = { type: "message", id: "e2", message: { role: "bashExecution", command: "ls", output: "a\nb\n" } };
	assert.equal(extractFullPart(entry, "output", undefined), "a\nb\n");
});

test("coordinates that do not resolve report nothing", () => {
	const entry = { type: "message", id: "e3", message: { role: "assistant", content: [{ type: "text", text: "hi" }] } };
	assert.equal(extractFullPart(entry, "text", 9), undefined, "index past the end");
	assert.equal(extractFullPart(entry, "thinking", 0), undefined, "wrong part for that block");
	assert.equal(extractFullPart(entry, "text", undefined), undefined, "an index is required for block parts");
	assert.equal(extractFullPart(undefined, "text", 0), undefined);
});
