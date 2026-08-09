import { existsSync } from "node:fs";
import { basename } from "node:path";

import {
	estimateTokens,
	type SessionEntry,
	type SessionInfo,
	SessionManager,
} from "@earendil-works/pi-coding-agent";

import { HttpError } from "./http.ts";
import type { EntryPageDto, ProjectDto, SessionDetailDto, SessionSummaryDto } from "./protocol.ts";
import { extractFullPart, type FullPart, slimEntry } from "./slim.ts";

/**
 * Read-only view over `~/.pi/agent/sessions`.
 *
 * Everything here works off the JSONL files directly — no AgentSession is
 * created. Browsing projects, sessions, and history must stay free of agent
 * startup cost, because that is what makes switching sessions feel instant.
 */

/**
 * `SessionManager.listAll()` walks and parses every session file (~230ms for 79
 * sessions on the dev machine). Cache it briefly so that a list screen doing
 * several requests in a row does not rescan each time. The TTL is far shorter
 * than any human refresh interval, so a pull-to-refresh still returns fresh data.
 */
const LIST_TTL_MS = 2_000;

interface Snapshot {
	sessions: SessionInfo[];
	byId: Map<string, SessionInfo>;
	byPath: Map<string, SessionInfo>;
	takenAt: number;
}

let snapshot: Snapshot | undefined;
let inFlight: Promise<Snapshot> | undefined;

/**
 * Sessions created through this server that have not hit disk yet.
 *
 * `SessionManager.create()` defers writing the file until the first entry is
 * appended, so a session can exist and be prompt-able while `listAll()` still
 * cannot see it. Without this, the client would 404 on the id it was just
 * handed. Entries are dropped once the real scan picks them up.
 */
const pending = new Map<string, SessionInfo>();

export function registerSession(info: SessionInfo): void {
	pending.set(info.id, info);
}

async function loadSnapshot(): Promise<Snapshot> {
	const scanned = await SessionManager.listAll();
	const byId = new Map<string, SessionInfo>();
	const byPath = new Map<string, SessionInfo>();
	for (const info of scanned) {
		byId.set(info.id, info);
		byPath.set(info.path, info);
		// It landed on disk; the placeholder has served its purpose.
		pending.delete(info.id);
	}

	const sessions = [...scanned, ...pending.values()];
	for (const info of pending.values()) {
		byId.set(info.id, info);
		byPath.set(info.path, info);
	}

	return { sessions, byId, byPath, takenAt: Date.now() };
}

async function getSnapshot(force = false): Promise<Snapshot> {
	if (!force && snapshot && Date.now() - snapshot.takenAt < LIST_TTL_MS) return snapshot;
	// Collapse concurrent callers onto one scan.
	inFlight ??= loadSnapshot()
		.then((next) => {
			snapshot = next;
			return next;
		})
		.finally(() => {
			inFlight = undefined;
		});
	return inFlight;
}

/** Drop the cache. Call after any write that changes the session set. */
export function invalidateSessionCache(): void {
	snapshot = undefined;
}

/** Remove a placeholder that never made it to disk (empty new session). */
export function dropPendingSession(id: string): void {
	pending.delete(id);
}

/** Sessions whose file (or pending placeholder) lives under `cwd`. */
export async function sessionsByCwd(cwd: string): Promise<SessionInfo[]> {
	const { sessions } = await getSnapshot();
	return sessions.filter((info) => info.cwd === cwd);
}

/**
 * Cwds of sessions forked from `path` (empty if none). Used to decide whether
 * deleting a parent would orphan forks in another workspace.
 */
export async function childCwds(path: string): Promise<string[]> {
	const { sessions } = await getSnapshot();
	return sessions.filter((info) => info.parentSessionPath === path).map((info) => info.cwd);
}

export async function listProjects(): Promise<ProjectDto[]> {
	const { sessions } = await getSnapshot();
	const byCwd = new Map<string, { count: number; lastModified: number }>();

	for (const info of sessions) {
		// Sessions from before cwd tracking have an empty string; group them out
		// of the way rather than creating a project row with no path.
		if (!info.cwd) continue;
		const modified = info.modified.getTime();
		const existing = byCwd.get(info.cwd);
		if (existing) {
			existing.count++;
			if (modified > existing.lastModified) existing.lastModified = modified;
		} else {
			byCwd.set(info.cwd, { count: 1, lastModified: modified });
		}
	}

	return [...byCwd.entries()]
		.map(([cwd, agg]) => ({
			cwd,
			name: basename(cwd) || cwd,
			sessionCount: agg.count,
			lastModified: new Date(agg.lastModified).toISOString(),
		}))
		.sort((a, b) => b.lastModified.localeCompare(a.lastModified));
}

export async function listSessions(cwd: string): Promise<SessionSummaryDto[]> {
	const snap = await getSnapshot();
	return snap.sessions
		.filter((info) => info.cwd === cwd)
		.map((info) => toSummary(info, snap))
		.sort((a, b) => b.modified.localeCompare(a.modified));
}

export async function findSession(id: string): Promise<SessionInfo> {
	let snap = await getSnapshot();
	let info = snap.byId.get(id);
	if (!info) {
		// A session created moments ago may predate the cached snapshot.
		snap = await getSnapshot(true);
		info = snap.byId.get(id);
	}
	if (!info) throw new HttpError(404, `No session with id ${id}`, "session_not_found");
	return info;
}

export async function toSummaryById(id: string): Promise<SessionSummaryDto> {
	const snap = await getSnapshot();
	return toSummary(await findSession(id), snap);
}

/**
 * Whether a session currently has a streaming agent.
 *
 * The read-only layer has no idea about live agents, so M2 injects the lookup
 * rather than this module importing the pool and creating a cycle.
 */
let runningProbe: (sessionId: string) => boolean = () => false;

export function setRunningProbe(probe: (sessionId: string) => boolean): void {
	runningProbe = probe;
}

export async function getSessionDetail(id: string): Promise<SessionDetailDto> {
	const snap = await getSnapshot();
	const info = await findSession(id);
	const sm = openIfWritten(info.path);
	const entries = sm?.buildContextEntries() ?? [];

	return {
		...toSummary(info, snap),
		...readSettings(entries),
		leafId: sm?.getLeafId() ?? null,
		totalEntries: entries.length,
		running: runningProbe(id),
	};
}

/**
 * Open a session file, or undefined when it has not been written yet.
 *
 * A session created via `POST /sessions` has no file until its first entry is
 * appended, and asking the client to special-case that is worse than reporting
 * an empty session.
 */
function openIfWritten(path: string): SessionManager | undefined {
	if (!existsSync(path)) return undefined;
	return SessionManager.open(path);
}

/**
 * Estimated token count of the active branch, without loading an agent.
 *
 * Uses the SDK's own per-message estimator so the number roughly matches what
 * the loaded agent would report. null when nothing can be read.
 */
export async function estimateSessionTokens(id: string): Promise<number | null> {
	try {
		const info = await findSession(id);
		const sm = openIfWritten(info.path);
		if (!sm) return null;
		let tokens = 0;
		for (const entry of sm.buildContextEntries()) {
			if (entry.type === "message") tokens += estimateTokens(entry.message);
		}
		return tokens;
	} catch {
		return null;
	}
}

/**
 * One page of history, newest-last, walking backwards from `before`.
 *
 * Uses `buildContextEntries()` rather than `getEntries()`: the session is a
 * tree, and only the active branch (with compaction applied) is what the user
 * is looking at. `getEntries()` would also hand back abandoned branches.
 */
export async function getEntryPage(id: string, before: string | undefined, limit: number): Promise<EntryPageDto> {
	const info = await findSession(id);
	const sm = openIfWritten(info.path);
	if (!sm) return { entries: [], hasMore: false, oldestId: null, leafId: null };
	const entries = sm.buildContextEntries();

	let end = entries.length;
	if (before !== undefined) {
		const idx = entries.findIndex((e) => e.id === before);
		if (idx === -1) {
			throw new HttpError(409, `Cursor ${before} is not on the active branch`, "stale_cursor");
		}
		end = idx;
	}

	const start = Math.max(0, end - limit);
	const page = entries.slice(start, end);

	return {
		entries: page.map(slimEntry),
		hasMore: start > 0,
		oldestId: page[0]?.id ?? null,
		leafId: sm.getLeafId(),
	};
}

/** The untruncated original of one shrunk part. */
export async function getFullPart(
	id: string,
	entryId: string,
	part: FullPart,
	index: number | undefined,
): Promise<{ content: string }> {
	const info = await findSession(id);
	const sm = SessionManager.open(info.path);
	const entry = sm.getEntry(entryId);
	if (!entry) throw new HttpError(404, `No entry ${entryId} in session ${id}`, "entry_not_found");

	const content = extractFullPart(entry, part, index);
	if (content === undefined) {
		throw new HttpError(404, `Entry ${entryId} has no ${part} part at index ${index ?? "-"}`, "part_not_found");
	}
	return { content };
}

/**
 * Recover the active model and thinking level by replaying the change entries
 * on the branch. Last one wins; absent means "whatever pi defaults to", which
 * the client renders as unset.
 */
function readSettings(entries: SessionEntry[]): Pick<SessionDetailDto, "model" | "thinkingLevel"> {
	let model: SessionDetailDto["model"] = null;
	let thinkingLevel = "";
	for (const entry of entries) {
		if (entry.type === "model_change") {
			model = { provider: entry.provider, modelId: entry.modelId };
		} else if (entry.type === "thinking_level_change") {
			thinkingLevel = entry.thinkingLevel;
		}
	}
	return { model, thinkingLevel };
}

/**
 * Project the SDK's SessionInfo onto the wire type.
 *
 * Notably drops `allMessagesText`, which concatenates the session's text for
 * local search. The client never uses it, so it is pure waste over the network.
 */
function toSummary(info: SessionInfo, snap: Snapshot): SessionSummaryDto {
	const summary: SessionSummaryDto = {
		id: info.id,
		cwd: info.cwd,
		created: info.created.toISOString(),
		modified: info.modified.toISOString(),
		messageCount: info.messageCount,
		firstMessage: info.firstMessage,
	};
	if (info.name) summary.name = info.name;
	if (info.parentSessionPath) {
		const parent = snap.byPath.get(info.parentSessionPath);
		if (parent) summary.parentSessionId = parent.id;
	}
	return summary;
}
