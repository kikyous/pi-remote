import assert from "node:assert/strict";
import { appendFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, test } from "node:test";

/**
 * `getAgentDir()` reads this on every call, so pointing it at a scratch tree is
 * enough to isolate the scan from the developer's real sessions. It has to be
 * set before the module under test resolves a path, not before it is imported.
 */
const root = mkdtempSync(join(tmpdir(), "pi-remote-scan-"));
process.env.PI_CODING_AGENT_DIR = root;

const { forget, idOfPath, invalidateIndex, knows, locate, registerPending, summaries } = await import("./scan.ts");

const CWD = "/tmp/project-a";
const sessionsDir = join(root, "sessions", `--${CWD.slice(1).replaceAll("/", "-")}--`);

/** A minimal but realistic session file: header, then messages. */
function writeSession(id: string, options: { name?: string; parentSessionPath?: string; texts?: string[] } = {}): string {
	const path = join(sessionsDir, `2026-08-12T00-00-00-000Z_${id}.jsonl`);
	const lines: string[] = [
		JSON.stringify({
			type: "session",
			version: 3,
			id,
			timestamp: "2026-08-12T00:00:00.000Z",
			cwd: CWD,
			...(options.parentSessionPath ? { parentSession: options.parentSessionPath } : {}),
		}),
	];
	let parentId: string | null = null;
	(options.texts ?? ["hello"]).forEach((text, i) => {
		const entryId = `${id}-e${i}`;
		lines.push(
			JSON.stringify({
				type: "message",
				id: entryId,
				parentId,
				timestamp: `2026-08-12T00:0${i}:00.000Z`,
				message: { role: i % 2 === 0 ? "user" : "assistant", content: text, timestamp: 1_786_500_000_000 + i * 1000 },
			}),
		);
		parentId = entryId;
	});
	if (options.name !== undefined) {
		lines.push(JSON.stringify({ type: "session_info", id: `${id}-n`, parentId, timestamp: "2026-08-12T00:10:00.000Z", name: options.name }));
	}
	writeFileSync(path, `${lines.join("\n")}\n`);
	return path;
}

before(() => {
	mkdirSync(sessionsDir, { recursive: true });
});

after(() => {
	rmSync(root, { recursive: true, force: true });
});

test("idOfPath reads the id out of the filename", () => {
	assert.equal(idOfPath("/x/2026-08-08T16-47-37-124Z_019fe245-dca4-7b37-801f-5868bb259b06.jsonl"), "019fe245-dca4-7b37-801f-5868bb259b06");
	// Not a session file, or no id to find: report nothing rather than guess.
	assert.equal(idOfPath("/x/notes.md"), undefined);
	assert.equal(idOfPath("/x/no-underscore.jsonl"), undefined);
});

test("summaries derives the same fields the list screen shows", async () => {
	writeSession("aaa", { texts: ["first question", "an answer"], name: "  Named  " });
	invalidateIndex();

	const all = await summaries();
	const one = all.find((s) => s.id === "aaa");
	assert.ok(one, "the session was found");
	assert.equal(one.cwd, CWD);
	assert.equal(one.messageCount, 2);
	// The first *user* message, and a name trimmed of its padding.
	assert.equal(one.firstMessage, "first question");
	assert.equal(one.name, "Named");
	// `modified` tracks the last message's own timestamp, not the file's mtime.
	assert.equal(one.modified.getTime(), 1_786_500_001_000);
	assert.equal(one.created.toISOString(), "2026-08-12T00:00:00.000Z");
});

test("a session with no user message still gets a preview", async () => {
	const path = join(sessionsDir, "2026-08-12T00-00-00-000Z_empty1.jsonl");
	writeFileSync(path, `${JSON.stringify({ type: "session", version: 3, id: "empty1", timestamp: "2026-08-12T00:00:00.000Z", cwd: CWD })}\n`);
	invalidateIndex();

	const one = (await summaries()).find((s) => s.id === "empty1");
	assert.ok(one);
	assert.equal(one.messageCount, 0);
	assert.equal(one.firstMessage, "(no messages)");
	// No activity to date it by, so the header's timestamp stands in.
	assert.equal(one.modified.toISOString(), "2026-08-12T00:00:00.000Z");
});

test("a file that is not a pi session is skipped, not fatal", async () => {
	writeFileSync(join(sessionsDir, "2026-08-12T00-00-00-000Z_junk01.jsonl"), "not json at all\n{}\n");
	invalidateIndex();

	const all = await summaries();
	assert.equal(all.find((s) => s.id === "junk01"), undefined);
	// Everything else still came back.
	assert.ok(all.find((s) => s.id === "aaa"));
});

test("only the file that changed is re-read", async () => {
	writeSession("bbb", { texts: ["b question"] });
	invalidateIndex();

	const first = await summaries();
	const untouchedBefore = first.find((s) => s.id === "aaa");
	assert.ok(untouchedBefore);

	// Append to bbb only. Its `(mtime, size)` moves; aaa's does not.
	appendFileSync(
		join(sessionsDir, "2026-08-12T00-00-00-000Z_bbb.jsonl"),
		`${JSON.stringify({ type: "message", id: "bbb-x", parentId: "bbb-e0", timestamp: "2026-08-12T00:05:00.000Z", message: { role: "user", content: "more", timestamp: 1_786_500_009_000 } })}\n`,
	);

	const second = await summaries();
	const changed = second.find((s) => s.id === "bbb");
	assert.ok(changed);
	assert.equal(changed.messageCount, 2, "the appended message was picked up");

	// The strongest available evidence that aaa was served from cache rather
	// than parsed again: the very same object came back.
	assert.equal(second.find((s) => s.id === "aaa"), untouchedBefore);
});

test("locate resolves id to path and cwd, and finds a file created later", async () => {
	const found = await locate("aaa");
	assert.ok(found);
	assert.equal(found.cwd, CWD);
	assert.match(found.path, /_aaa\.jsonl$/);

	// A brand-new file postdates the index; the miss has to trigger a rescan
	// rather than a 404, or a just-created session would be unreachable.
	const path = writeSession("ccc");
	const late = await locate("ccc");
	assert.ok(late, "a file written after the last scan is still found");
	assert.equal(late.path, path);

	assert.equal(await locate("nosuchsession"), undefined);
});

test("a pending session is visible before it reaches disk", async () => {
	const path = join(sessionsDir, "2026-08-12T00-00-00-000Z_ddd.jsonl");
	registerPending({
		id: "ddd",
		path,
		cwd: CWD,
		created: new Date("2026-08-12T01:00:00.000Z"),
		modified: new Date("2026-08-12T01:00:00.000Z"),
		messageCount: 0,
		firstMessage: "",
	});

	const located = await locate("ddd");
	assert.ok(located, "prompt-able before its first append");
	assert.equal(located.path, path);
	assert.ok((await summaries()).some((s) => s.id === "ddd"));

	// Once the real file lands, the placeholder gives way to the scanned truth.
	writeSession("ddd", { texts: ["real content"] });
	invalidateIndex();
	const settled = (await summaries()).filter((s) => s.id === "ddd");
	assert.equal(settled.length, 1, "no duplicate row while both exist");
	assert.equal(settled[0]?.firstMessage, "real content");
});

test("forget drops a deleted session from the index", async () => {
	assert.ok(knows("ccc"));
	rmSync(join(sessionsDir, "2026-08-12T00-00-00-000Z_ccc.jsonl"));
	forget("ccc");

	assert.equal(knows("ccc"), false);
	assert.equal(await locate("ccc"), undefined);
	assert.equal((await summaries()).find((s) => s.id === "ccc"), undefined);
});

test("summaries come back newest first", async () => {
	const all = await summaries();
	for (let i = 1; i < all.length; i++) {
		assert.ok(all[i - 1]!.modified.getTime() >= all[i]!.modified.getTime(), "sorted by modified, descending");
	}
});
