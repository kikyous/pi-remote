import { basename } from "node:path";

import { estimateTokens, type SessionEntry } from "@earendil-works/pi-coding-agent";

import { HttpError } from "./http.ts";
import type { EntryPageDto, ProjectDto, SessionDetailDto, SessionSummaryDto } from "./protocol.ts";
import { getModel } from "./sessions/model.ts";
import { idOfPath, knows, type Located, locate, type SessionSummary, summaries, summaryOf } from "./sessions/scan.ts";
import { extractFullPart, type FullPart, slimEntry } from "./slim.ts";

/**
 * Read-only view over `~/.pi/agent/sessions`.
 *
 * Everything here works off the JSONL files directly — no AgentSession is
 * created. Browsing projects, sessions, and history must stay free of agent
 * startup cost, because that is what makes switching sessions feel instant.
 *
 * The two costs that used to live here — a full `listAll()` rescan for what was
 * really an `id → path` lookup, and a fresh parse per request — now belong to
 * `sessions/scan.ts` and `sessions/model.ts`. This file is just the projection
 * onto the wire types.
 */

export { dropPending as dropPendingSession, registerPending as registerSession } from "./sessions/scan.ts";

/** Resolve a session id to its file, or 404. */
export async function requireLocated(id: string): Promise<Located> {
	const located = await locate(id);
	if (!located) throw new HttpError(404, `No session with id ${id}`, "session_not_found");
	return located;
}

/** Sessions whose file (or pending placeholder) lives under `cwd`. */
export async function sessionsByCwd(cwd: string): Promise<SessionSummary[]> {
	return (await summaries()).filter((info) => info.cwd === cwd);
}

/**
 * Cwds of sessions forked from `path` (empty if none). Used to decide whether
 * deleting a parent would orphan forks in another workspace.
 */
export async function childCwds(path: string): Promise<string[]> {
	return (await summaries()).filter((info) => info.parentSessionPath === path).map((info) => info.cwd);
}

export async function listProjects(): Promise<ProjectDto[]> {
	const sessions = await summaries();
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
	return (await sessionsByCwd(cwd)).map(toSummary).sort((a, b) => b.modified.localeCompare(a.modified));
}

/**
 * Whether a session currently has a streaming agent.
 *
 * The read-only layer has no idea about live agents, so the lookup is injected
 * rather than imported, which would create a cycle.
 */
let runningProbe: (sessionId: string) => boolean = () => false;

export function setRunningProbe(probe: (sessionId: string) => boolean): void {
	runningProbe = probe;
}

export async function getSessionDetail(id: string): Promise<SessionDetailDto> {
	const info = await summaryOf(id);
	if (!info) throw new HttpError(404, `No session with id ${id}`, "session_not_found");
	// One parse, shared with the `/entries` request that follows on the client's
	// session-open and with the token estimate below.
	const model = getModel(info);
	const entries = model?.entries ?? [];

	return {
		...toSummary(info),
		...readSettings(entries),
		leafId: model?.leafId ?? null,
		totalEntries: entries.length,
		running: runningProbe(id),
	};
}

/**
 * Estimated token count of the active branch, without loading an agent.
 *
 * Uses the SDK's own per-message estimator so the number roughly matches what
 * the loaded agent would report. null when nothing can be read.
 */
export async function estimateSessionTokens(id: string): Promise<number | null> {
	try {
		const model = getModel(await requireLocated(id));
		if (!model) return null;
		let tokens = 0;
		for (const entry of model.entries) {
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
 * The page comes off the active branch with compaction applied — a session is a
 * tree, and abandoned branches are not what the user is looking at.
 */
export async function getEntryPage(id: string, before: string | undefined, limit: number): Promise<EntryPageDto> {
	const model = getModel(await requireLocated(id));
	// A session created moments ago has no file until its first append.
	if (!model) return { entries: [], hasMore: false, oldestId: null, leafId: null };
	const entries = model.entries;

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
		leafId: model.leafId,
	};
}

/** The untruncated original of one shrunk part. */
export async function getFullPart(
	id: string,
	entryId: string,
	part: FullPart,
	index: number | undefined,
): Promise<{ content: string }> {
	const model = getModel(await requireLocated(id));
	const entry = model?.entry(entryId);
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

/** Project a scanned summary onto the wire type. */
function toSummary(info: SessionSummary): SessionSummaryDto {
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
		// The parent's id is in its filename, so this needs no lookup — but it
		// is only reported while the parent is actually still on disk, or the
		// client would offer to open a session that no longer exists.
		const parentId = idOfPath(info.parentSessionPath);
		if (parentId && knows(parentId)) summary.parentSessionId = parentId;
	}
	return summary;
}
