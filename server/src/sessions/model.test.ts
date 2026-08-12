import assert from "node:assert/strict";
import { appendFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";

import type { SessionEntry } from "@earendil-works/pi-coding-agent";

import { evictionPlan, forgetModel, getModel, setLiveSource } from "./model.ts";
import type { Located } from "./scan.ts";

const root = mkdtempSync(join(tmpdir(), "pi-remote-model-"));
after(() => rmSync(root, { recursive: true, force: true }));

const CWD = "/tmp/project-m";

function writeSession(id: string, texts: string[]): Located {
	mkdirSync(root, { recursive: true });
	const path = join(root, `2026-08-12T00-00-00-000Z_${id}.jsonl`);
	const lines = [JSON.stringify({ type: "session", version: 3, id, timestamp: "2026-08-12T00:00:00.000Z", cwd: CWD })];
	let parentId: string | null = null;
	texts.forEach((text, i) => {
		const entryId = `${id}-e${i}`;
		lines.push(
			JSON.stringify({
				type: "message",
				id: entryId,
				parentId,
				timestamp: `2026-08-12T00:0${i}:00.000Z`,
				message: { role: "user", content: text },
			}),
		);
		parentId = entryId;
	});
	writeFileSync(path, `${lines.join("\n")}\n`);
	return { id, path, cwd: CWD };
}

function append(located: Located, entryId: string, parentId: string): void {
	appendFileSync(
		located.path,
		`${JSON.stringify({ type: "message", id: entryId, parentId, timestamp: "2026-08-12T01:00:00.000Z", message: { role: "user", content: "added" } })}\n`,
	);
}

test("an unchanged file is parsed once and then handed back as-is", () => {
	const located = writeSession("m1", ["one", "two"]);

	const first = getModel(located);
	assert.ok(first);
	assert.equal(first.entries.length, 2);
	// Same `(mtime, size)` means the parse is provably current, so the very same
	// object comes back rather than a second walk of the file.
	assert.equal(getModel(located), first);
});

test("an appended file is re-parsed", () => {
	const located = writeSession("m2", ["one"]);
	const before = getModel(located);
	assert.ok(before);
	assert.equal(before.entries.length, 1);

	append(located, "m2-x", "m2-e0");

	const after = getModel(located);
	assert.ok(after);
	assert.notEqual(after, before, "the stamp moved, so the cache entry was replaced");
	assert.equal(after.entries.length, 2);
	assert.equal(after.leafId, "m2-x");
});

test("a session with no file yet has no model", () => {
	assert.equal(getModel({ id: "ghost", path: join(root, "nope_ghost.jsonl"), cwd: CWD }), undefined);
});

test("entry() reaches entries by id, for /full", () => {
	const located = writeSession("m3", ["findable"]);
	const model = getModel(located);
	assert.ok(model);
	const entry = model.entry("m3-e0");
	assert.equal(entry?.type, "message");
	assert.equal(model.entry("no-such-entry"), undefined);
});

test("forgetModel drops the parse so a recreated file is not served stale", () => {
	const located = writeSession("m4", ["original"]);
	const first = getModel(located);
	assert.ok(first);

	forgetModel(located.path);
	// Rewritten with the same length, so `(mtime, size)` could plausibly collide;
	// after forgetting there is nothing to collide with.
	writeSession("m4", ["replaced"]);
	const second = getModel(located);
	assert.notEqual(second, first);
});

test("a loaded agent's tree outranks the file", () => {
	const located = writeSession("m5", ["on disk"]);
	const onDisk = getModel(located);
	assert.ok(onDisk);
	assert.equal(onDisk.entries.length, 1);

	const fake: SessionEntry[] = [
		{ type: "message", id: "live-1", parentId: null, timestamp: "2026-08-12T02:00:00.000Z", message: { role: "user", content: "not flushed yet" } } as unknown as SessionEntry,
		{ type: "message", id: "live-2", parentId: "live-1", timestamp: "2026-08-12T02:00:01.000Z", message: { role: "assistant", content: "either" } } as unknown as SessionEntry,
	];
	setLiveSource((id) =>
		id === "m5"
			? { buildContextEntries: () => fake, getLeafId: () => "live-2", getEntry: (wanted) => fake.find((e) => e.id === wanted) }
			: undefined,
	);

	try {
		const live = getModel(located);
		assert.ok(live);
		assert.equal(live.entries.length, 2, "the in-memory tree won, including its unflushed append");
		assert.equal(live.leafId, "live-2");
		// Never cached: the agent mutates its tree in place, so no stamp could
		// stay valid. Each call must re-read it.
		assert.notEqual(getModel(located), live);
	} finally {
		setLiveSource(() => undefined);
	}

	// With the agent gone, the file is authoritative again.
	assert.equal(getModel(located)?.entries.length, 1);
});

test("eviction keeps the newest parse and drops least-recently-used first", () => {
	// Least-recently-used first; "c" was just added.
	const order: Array<[string, number]> = [
		["a", 100],
		["b", 100],
		["c", 100],
	];
	assert.deepEqual(evictionPlan(order, "c", 300, 300), [], "inside budget, nothing goes");
	assert.deepEqual(evictionPlan(order, "c", 300, 250), ["a"], "one eviction is enough");
	assert.deepEqual(evictionPlan(order, "c", 300, 50), ["a", "b"], "the just-added entry is never dropped");
});

test("a session larger than the whole budget stays servable", () => {
	assert.deepEqual(evictionPlan([["huge", 40_000]], "huge", 40_000, 25_000), []);
	// And it does clear room by dropping everything else first.
	assert.deepEqual(evictionPlan([["small", 10], ["huge", 40_000]], "huge", 40_010, 25_000), ["small"]);
});
