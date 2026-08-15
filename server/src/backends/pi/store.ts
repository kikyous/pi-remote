import { basename } from "node:path";

import { estimateTokens, type SessionEntry } from "@earendil-works/pi-coding-agent";

import { HttpError } from "../../http.ts";
import type { ItemPageDto, ProjectDto, SessionDetailDto, SessionSummaryDto } from "../../protocol.ts";
import { extractFullPart, parseRef } from "../../refs.ts";
import { getModel } from "./model.ts";
import { idOfPath, knows, type Located, locate, type SessionSummary, summaries, summaryOf } from "./scan.ts";

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

export { dropPending as dropPendingSession, registerPending as registerSession } from "./scan.ts";

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

/**
 * Live model and thinking level from a loaded agent, which outrank the file.
 *
 * A brand-new session has no file yet, and a session whose model just changed may
 * not have flushed the entry — in both cases the agent in memory is the truth.
 * Injected for the same reason as [setRunningProbe].
 */
let liveStateProbe: (sessionId: string) => LiveSessionState | undefined = () => undefined;

export interface LiveSessionState {
	model: { provider: string; modelId: string } | null;
	thinkingLevel: string;
	availableThinkingLevels: string[];
	/** Present when the agent knows a name the file may not have flushed yet. */
	name?: string;
}

export function setLiveStateProbe(probe: (sessionId: string) => LiveSessionState | undefined): void {
	liveStateProbe = probe;
}

/** The settings half of a session. What is *running* lives in the status push. */
export async function getDetail(id: string): Promise<SessionDetailDto> {
	const info = await summaryOf(id);
	if (!info) throw new HttpError(404, `No session with id ${id}`, "session_not_found");
	// One parse, shared with the item page built alongside it in a `hello`.
	const model = getModel(info);
	const live = liveStateProbe(id);

	return {
		id: info.id,
		cwd: info.cwd,
		...(info.name ? { name: info.name } : {}),
		firstMessage: info.firstMessage,
		...readSettings(model?.branch() ?? []),
		...(live
			? {
					model: live.model,
					thinkingLevel: live.thinkingLevel,
					availableThinkingLevels: live.availableThinkingLevels,
					...(live.name ? { name: live.name } : {}),
				}
			: {}),
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
 * One page of the conversation, newest-last, walking backwards from `before`.
 *
 * Pages are cut out of the item list for the *whole* active branch, not built per
 * page: a tool call and its result are separate entries, and a page boundary
 * between them would leave the call rendered as "running" with its output stranded
 * on the other side. Building the branch once is what makes that impossible — and
 * it costs nothing, being memoized on the cached parse.
 */
export async function getItemPage(id: string, before: string | undefined, limit: number): Promise<ItemPageDto> {
	return itemPageOf(await requireLocated(id), before, limit);
}

/**
 * The synchronous half of [getItemPage].
 *
 * A `hello` has to read the page and the live sequence number in the same tick:
 * with an `await` between them, an entry landing in the gap ends up in neither
 * the snapshot nor the pushes that follow it, and its message vanishes. Callers
 * that already hold the `Located` use this and stay atomic.
 */
export function itemPageOf(located: Located, before: string | undefined, limit: number): ItemPageDto {
	const model = getModel(located);
	// A session created moments ago has no file until its first append.
	if (!model) return { items: [], hasMore: false, oldest: null };
	const all = model.items();

	let end = all.length;
	if (before !== undefined) {
		const idx = all.findIndex((item) => item.id === before);
		if (idx === -1) {
			throw new HttpError(409, `Cursor ${before} is not on the active branch`, "stale_cursor");
		}
		end = idx;
	}

	const start = Math.max(0, end - limit);
	return {
		items: all.slice(start, end),
		hasMore: start > 0,
		oldest: all[start]?.id ?? null,
	};
}

/** The original behind a `more` handle, whatever kind of content it points at. */
export async function getFullByRef(id: string, ref: string): Promise<{ content: string }> {
	const parsed = parseRef(ref);
	if (!parsed) throw new HttpError(400, `Malformed ref ${ref}`, "bad_ref");

	const model = getModel(await requireLocated(id));
	const entry = model?.entry(parsed.entryId);
	if (!entry) throw new HttpError(404, `No entry ${parsed.entryId} in session ${id}`, "entry_not_found");

	const content = extractFullPart(entry, parsed.part, parsed.index);
	if (content === undefined) {
		throw new HttpError(404, `Nothing at ${ref}`, "part_not_found");
	}
	return { content };
}

/**
 * Recover the active model and thinking level by replaying the change entries
 * on the branch. Last one wins; absent means "whatever pi defaults to", which
 * the client renders as unset.
 *
 * Takes the *unpruned* branch (`model.branch()`), and falls back to the model an
 * assistant message was answered by — both because that is what pi's own
 * `getSessionContextSettings` does. Reading the compaction-pruned context view
 * instead loses the settings of every compacted session: `model_change` is
 * written once, at the top of the file, and the summary cuts it away.
 */
function readSettings(entries: SessionEntry[]): Pick<SessionDetailDto, "model" | "thinkingLevel"> {
	let model: SessionDetailDto["model"] = null;
	let thinkingLevel = "";
	for (const entry of entries) {
		if (entry.type === "model_change") {
			model = { provider: entry.provider, modelId: entry.modelId };
		} else if (entry.type === "thinking_level_change") {
			thinkingLevel = entry.thinkingLevel;
		} else if (entry.type === "message" && entry.message.role === "assistant") {
			const answered = entry.message as { provider?: string; model?: string };
			if (answered.provider && answered.model) {
				model = { provider: answered.provider, modelId: answered.model };
			}
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
