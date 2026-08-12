import { createReadStream } from "node:fs";
import { readdir, stat } from "node:fs/promises";
import { basename, join } from "node:path";
import { createInterface } from "node:readline";

import { getAgentDir } from "@earendil-works/pi-coding-agent";

/**
 * The cheap half of the read path: find a session, and summarize it for a list.
 *
 * This replaces `SessionManager.listAll()`, which parses every session file on
 * every call — measured at **476ms for 135 sessions**, and it used to sit on the
 * critical path of every request that needed nothing but `id → path`.
 *
 * Two ideas do the work:
 *
 *  1. **A session's id is in its filename** (`<timestamp>_<id>.jsonl`, verified
 *     against all 135 local sessions). So `locate()` costs a `readdir`
 *     (2.6ms for the whole corpus) and never parses anything.
 *  2. **A session file is append-only**, so `(mtimeMs, size)` is a complete
 *     cache key. A rescan re-reads only the files that actually changed; a list
 *     refresh where nothing moved costs `readdir` + `stat` — 5.3ms.
 *
 * There is deliberately no TTL anywhere in here. Freshness comes from `stat`,
 * which is both cheaper and *more* accurate than the 2-second window it
 * replaces: a session appended to by an external pi TUI shows up at once.
 */

/**
 * What a list screen needs about a session.
 *
 * Mirrors the SDK's `SessionInfo` minus `allMessagesText`, which concatenates
 * the whole conversation for local search. Nothing here consumes it, and
 * building it doubles both the read cost and the retained memory.
 */
export interface SessionSummary {
	id: string;
	path: string;
	cwd: string;
	/** User-defined display name (`/name`), if set. */
	name?: string;
	/** Path to the parent session, when this one was forked. */
	parentSessionPath?: string;
	created: Date;
	modified: Date;
	messageCount: number;
	firstMessage: string;
}

/** Just enough to open or write a session, without summarizing it. */
export interface Located {
	id: string;
	path: string;
	cwd: string;
}

interface FileStamp {
	path: string;
	mtimeMs: number;
	size: number;
}

/**
 * id → file. Built from filenames alone.
 *
 * Never revalidated per lookup: a session's path is fixed for its lifetime, so
 * a hit is permanently good. Only a miss triggers a rescan.
 */
const files = new Map<string, FileStamp>();

/** path → the header's immutable facts, so `locate` reads a file at most once. */
const headers = new Map<string, { id: string; cwd: string; created: Date; parentSessionPath?: string }>();

/** path → summary, valid while `(mtimeMs, size)` still matches. */
const cached = new Map<string, { stamp: string; summary: SessionSummary }>();

/**
 * Sessions created here that have not hit disk yet.
 *
 * `SessionManager.create()` defers the first write until the first entry is
 * appended, so a session can be prompt-able while no file exists to find.
 * Entries are dropped once a scan picks the real file up.
 */
const pending = new Map<string, SessionSummary>();

let indexed = false;
let scanning: Promise<void> | undefined;

function sessionsRoot(): string {
	return join(getAgentDir(), "sessions");
}

function stampOf(file: FileStamp): string {
	return `${file.mtimeMs}:${file.size}`;
}

/** The id embedded in `<timestamp>_<id>.jsonl`, or undefined if it does not fit. */
function idFromName(name: string): string | undefined {
	if (!name.endsWith(".jsonl")) return undefined;
	const stem = basename(name, ".jsonl");
	const cut = stem.lastIndexOf("_");
	if (cut === -1) return undefined;
	const id = stem.slice(cut + 1);
	return id.length > 0 ? id : undefined;
}

/**
 * Rebuild the id → file map. `readdir` + `stat` only: no file is opened.
 *
 * Concurrent callers share one pass — a list screen firing several requests at
 * once should not walk the tree several times.
 */
async function reindex(): Promise<void> {
	if (scanning) return scanning;
	scanning = (async () => {
		const root = sessionsRoot();
		let dirs: string[];
		try {
			dirs = (await readdir(root, { withFileTypes: true }))
				.filter((d) => d.isDirectory() || d.isSymbolicLink())
				.map((d) => join(root, d.name));
		} catch {
			// No sessions directory yet: a fresh pi install.
			files.clear();
			indexed = true;
			return;
		}

		const found = new Map<string, FileStamp>();
		await Promise.all(
			dirs.map(async (dir) => {
				let names: string[];
				try {
					names = await readdir(dir);
				} catch {
					return;
				}
				await Promise.all(
					names.map(async (name) => {
						const id = idFromName(name);
						if (id === undefined) return;
						const path = join(dir, name);
						try {
							const st = await stat(path);
							found.set(id, { path, mtimeMs: st.mtimeMs, size: st.size });
						} catch {
							// Deleted between readdir and stat.
						}
					}),
				);
			}),
		);

		files.clear();
		for (const [id, file] of found) files.set(id, file);
		indexed = true;
	})().finally(() => {
		scanning = undefined;
	});
	return scanning;
}

/** Force the next lookup to walk the tree. Call after creating or deleting a file. */
export function invalidateIndex(): void {
	indexed = false;
}

/**
 * The id of the session stored at `path`, from the filename alone.
 *
 * Lets a fork resolve its parent's id without a lookup over every session.
 */
export function idOfPath(path: string): string | undefined {
	return idFromName(basename(path));
}

/** Whether a session with this id is currently on disk. Index-only, no I/O. */
export function knows(id: string): boolean {
	return files.has(id);
}

export function registerPending(summary: SessionSummary): void {
	pending.set(summary.id, summary);
	invalidateIndex();
}

/** Drop a placeholder that never reached disk (an empty new session). */
export function dropPending(id: string): void {
	pending.delete(id);
}

/** Forget everything about a session. Call after deleting its file. */
export function forget(id: string): void {
	const file = files.get(id);
	if (file) {
		cached.delete(file.path);
		headers.delete(file.path);
	}
	files.delete(id);
	pending.delete(id);
	invalidateIndex();
}

/**
 * Resolve `id` to a path and cwd without parsing the session.
 *
 * The cwd comes from the header — the first line — and is cached forever,
 * because a session's header is written once and never rewritten.
 */
export async function locate(id: string): Promise<Located | undefined> {
	if (!indexed) await reindex();
	let file = files.get(id);
	if (!file) {
		// A session created moments ago may postdate the last walk.
		await reindex();
		file = files.get(id);
	}
	if (!file) {
		const placeholder = pending.get(id);
		return placeholder ? { id, path: placeholder.path, cwd: placeholder.cwd } : undefined;
	}

	const known = headers.get(file.path);
	if (known) return { id, path: file.path, cwd: known.cwd };

	const header = await readHeader(file.path);
	if (!header) return undefined;
	headers.set(file.path, header);
	return { id, path: file.path, cwd: header.cwd };
}

/** How many session files to read at once when the cache is cold. */
const READ_CONCURRENCY = 10;

/** Every session, newest first. Only files that changed are re-read. */
export async function summaries(): Promise<SessionSummary[]> {
	// Always re-walk: the index is `readdir`-only, so it must be refreshed for
	// `(mtime, size)` to be current before deciding what needs re-reading.
	await reindex();

	const out: SessionSummary[] = [];
	const stale: Array<[string, FileStamp]> = [];
	for (const [id, file] of files) {
		pending.delete(id);
		const hit = cached.get(file.path);
		if (hit?.stamp === stampOf(file)) out.push(hit.summary);
		else stale.push([id, file]);
	}

	// A cold cache means reading every file; bound the open descriptors.
	let next = 0;
	await Promise.all(
		Array.from({ length: Math.min(READ_CONCURRENCY, stale.length) }, async () => {
			while (next < stale.length) {
				const [id, file] = stale[next++]!;
				const summary = await readSummary(id, file);
				if (!summary) continue;
				cached.set(file.path, { stamp: stampOf(file), summary });
				out.push(summary);
			}
		}),
	);

	// Drop cache entries for files that are gone.
	if (cached.size > files.size) {
		const alive = new Set([...files.values()].map((f) => f.path));
		for (const path of cached.keys()) if (!alive.has(path)) cached.delete(path);
	}

	out.push(...pending.values());
	out.sort((a, b) => b.modified.getTime() - a.modified.getTime());
	return out;
}

/** One session's summary, or undefined when it does not exist. */
export async function summaryOf(id: string): Promise<SessionSummary | undefined> {
	if (!indexed) await reindex();
	let file = files.get(id);
	if (!file) {
		await reindex();
		file = files.get(id);
	}
	if (!file) return pending.get(id);

	const stamp = stampOf(file);
	const hit = cached.get(file.path);
	if (hit?.stamp === stamp) return hit.summary;

	const summary = await readSummary(id, file);
	if (!summary) return undefined;
	cached.set(file.path, { stamp, summary });
	pending.delete(id);
	return summary;
}

/**
 * A live run appends every few seconds, and there is deliberately nothing to
 * call here when it does.
 *
 * The previous design dropped the entire session cache on every appended entry
 * (`invalidateSessionCache()`), which put a 476ms rescan behind the next
 * request. Patching the cached summary in place instead is tempting but
 * unsound: keeping its `(mtime, size)` stamp valid would need a `stat`, and the
 * SDK emits `entry_appended` *before* the write reaches disk — the same race
 * that forced two `setImmediate` re-stats elsewhere. A stamp captured pre-write
 * would look valid forever and serve a stale summary permanently.
 *
 * So the stamp stays the only judge of freshness: the append changes it, the
 * next `summaries()` re-reads that one file, and every other file is skipped.
 */

/** Read only the first line: the session header. */
async function readHeader(path: string): Promise<{ id: string; cwd: string; created: Date; parentSessionPath?: string } | undefined> {
	const stream = createReadStream(path, { encoding: "utf8" });
	const rl = createInterface({ input: stream, crlfDelay: Infinity });
	try {
		for await (const line of rl) {
			if (line.length === 0) continue;
			const entry = parse(line);
			if (!entry || entry.type !== "session") return undefined;
			return headerFacts(entry);
		}
	} catch {
		return undefined;
	} finally {
		rl.close();
		stream.destroy();
	}
	return undefined;
}

function headerFacts(header: Record<string, unknown>): { id: string; cwd: string; created: Date; parentSessionPath?: string } {
	const created = typeof header.timestamp === "string" ? new Date(header.timestamp) : new Date(0);
	return {
		id: typeof header.id === "string" ? header.id : "",
		cwd: typeof header.cwd === "string" ? header.cwd : "",
		created,
		...(typeof header.parentSession === "string" ? { parentSessionPath: header.parentSession } : {}),
	};
}

/**
 * Stream one file into a summary.
 *
 * Mirrors the SDK's internal `buildSessionInfo` (not exported) field for field,
 * so the numbers the list screen shows do not move. One deliberate difference:
 * `allMessagesText` is not built — see [SessionSummary].
 *
 * Migration is not applied. `SessionManager.open()` would upgrade a pre-v3
 * file, which could in principle shift a preview or a count for a legacy
 * session; every local session is v3, and a slightly different preview line is
 * a far better trade than parsing 135 trees to render a list.
 */
async function readSummary(id: string, file: FileStamp): Promise<SessionSummary | undefined> {
	let header: Record<string, unknown> | undefined;
	let messageCount = 0;
	let firstMessage = "";
	let name: string | undefined;
	let lastActivity = 0;

	const stream = createReadStream(file.path, { encoding: "utf8" });
	const rl = createInterface({ input: stream, crlfDelay: Infinity });
	try {
		for await (const line of rl) {
			if (line.length === 0) continue;
			const entry = parse(line);
			if (!entry) continue;

			if (!header) {
				// The first parsable entry has to be the header, or this is not a
				// pi session file at all.
				if (entry.type !== "session") return undefined;
				header = entry;
				continue;
			}

			if (entry.type === "session_info") {
				// Latest wins, including an explicit clear back to unnamed.
				const raw = typeof entry.name === "string" ? entry.name.trim() : "";
				name = raw.length > 0 ? raw : undefined;
				continue;
			}
			if (entry.type !== "message") continue;
			messageCount++;

			const message = entry.message;
			if (!isRecord(message) || typeof message.role !== "string" || !("content" in message)) continue;
			if (message.role !== "user" && message.role !== "assistant") continue;

			const at =
				typeof message.timestamp === "number"
					? message.timestamp
					: typeof entry.timestamp === "string"
						? Date.parse(entry.timestamp)
						: Number.NaN;
			if (Number.isFinite(at)) lastActivity = Math.max(lastActivity, at);

			if (firstMessage.length === 0 && message.role === "user") {
				firstMessage = textOf(message.content);
			}
		}
	} catch {
		return undefined;
	} finally {
		rl.close();
		stream.destroy();
	}

	if (!header) return undefined;
	const facts = headerFacts(header);
	headers.set(file.path, facts);

	const headerTime = facts.created.getTime();
	const modified =
		lastActivity > 0
			? new Date(lastActivity)
			: Number.isNaN(headerTime)
				? new Date(file.mtimeMs)
				: facts.created;

	return {
		id: facts.id || id,
		path: file.path,
		cwd: facts.cwd,
		...(name !== undefined ? { name } : {}),
		...(facts.parentSessionPath !== undefined ? { parentSessionPath: facts.parentSessionPath } : {}),
		created: facts.created,
		modified,
		messageCount,
		firstMessage: firstMessage || "(no messages)",
	};
}

/** Message content is a string, or an array of blocks of which text ones count. */
export function textOf(content: unknown): string {
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	return content
		.filter((block): block is { type: string; text?: string } => isRecord(block) && block.type === "text")
		.map((block) => block.text ?? "")
		.join(" ");
}

function parse(line: string): Record<string, unknown> | undefined {
	try {
		const value = JSON.parse(line) as unknown;
		return isRecord(value) ? value : undefined;
	} catch {
		return undefined;
	}
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}
