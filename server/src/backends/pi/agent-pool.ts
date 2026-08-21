import { existsSync, mkdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

import {
	type AgentSession,
	type AgentSessionEvent,
	createAgentSession,
	type SessionEntry,
	SessionManager,
} from "@earendil-works/pi-coding-agent";

import { HttpError } from "../../http.ts";
import { getModelRuntime } from "./bootstrap.ts";
import { createCoalescer } from "../../live/coalesce.ts";
import { type LiveSession, publish } from "../../live/hub.ts";
import type { ThinkingLevel } from "../../protocol.ts";
import { createTranslator, type Translator } from "./translate.ts";
import type { EntryTree } from "./model.ts";
import { registerSession, requireLocated } from "./store.ts";

/**
 * Owns the live `AgentSession` objects.
 *
 * Browsing history never comes through here — that reads JSONL directly. An
 * agent is created only when someone actually prompts, and is disposed once
 * nobody is watching, so switching between sessions stays free.
 *
 * Sequence assignment, subscriber fan-out and catch-up bookkeeping are not here:
 * none of that is about pi, so it lives in `live/hub.ts` and is shared with any
 * other backend.
 */

/** How long a session with no subscribers and no active run stays loaded. */
const IDLE_TIMEOUT_MS = 10 * 60 * 1000;
const SWEEP_INTERVAL_MS = 60 * 1000;

export interface LiveAgent extends LiveSession {
	session: AgentSession;
	path: string;
	unsubscribe: () => void;
	/** SDK events → item mutations. The only consumer of AgentSessionEvent. */
	view: Translator;
	/** Serializes prompt admission. See `withPromptLock`. */
	promptChain: Promise<unknown>;
	/** File identity as we last knew it, to notice writes we did not make. */
	knownSize: number;
	knownMtimeMs: number;
}

const agents = new Map<string, LiveAgent>();
const starting = new Map<string, Promise<LiveAgent>>();

let sweeper: NodeJS.Timeout | undefined;

export function isRunning(sessionId: string): boolean {
	return agents.get(sessionId)?.session.isStreaming ?? false;
}

export function isLoaded(sessionId: string): boolean {
	return agents.has(sessionId);
}

export function getLoaded(sessionId: string): LiveAgent | undefined {
	return agents.get(sessionId);
}

/**
 * The in-memory entry tree of a loaded session, for `sessions/model.ts`.
 *
 * A loaded agent normally outranks the file: it may hold appends that have not
 * been flushed yet, so reading the file behind its back serves a view one entry
 * short. Wired up in `index.ts` rather than imported there, to keep the
 * read-only layer free of a dependency on this module.
 *
 * The exception is a file somebody else appended to. Our tree does not contain
 * those entries and will not until the next write acquires and reloads, so
 * handing it to a reader would hide an external pi TUI's messages — which the
 * file itself shows fine. Declining here sends the reader to the file, and the
 * agent reloads on its own schedule.
 */
export function liveTree(sessionId: string): EntryTree | undefined {
	const live = agents.get(sessionId);
	if (!live || wasWrittenByOthers(live)) return undefined;
	return live.session.sessionManager;
}

/**
 * Get the live session, creating it if needed.
 *
 * Concurrent callers share one startup: creating two AgentSessions for the same
 * file would give it two independent writers and corrupt the entry tree.
 *
 * @param forWrite Pass true when the caller is about to append (prompt, model
 *   change, rename). Only then is a stale in-memory tree a hazard, and only
 *   then is it worth tearing the session down to reload — doing it on every
 *   acquire would drop live WebSocket subscriptions mid-run.
 */
export async function acquire(sessionId: string, forWrite = false): Promise<LiveAgent> {
	const existing = agents.get(sessionId);
	if (existing) {
		if (forWrite && wasWrittenByOthers(existing)) {
			// A pi TUI (or anything else) appended to this file behind our back.
			// Our in-memory tree and leaf are now stale; appending on top of them
			// would fork the session at the wrong parent.
			console.warn(`[${sessionId}] session file changed externally — reloading`);
			return reload(existing);
		}
		existing.touchedAt = Date.now();
		return existing;
	}

	const inFlight = starting.get(sessionId);
	if (inFlight) return inFlight;

	const promise = create(sessionId).finally(() => starting.delete(sessionId));
	starting.set(sessionId, promise);
	return promise;
}

/**
 * Called when a session was replaced under its subscribers, so they can be sent
 * a fresh snapshot. Set by `ws.ts`, which is where `hello` is built — the pool
 * has no business reaching into the read path to assemble one.
 */
let onResync: (sessionId: string) => void = () => {};

export function setResyncHandler(handler: (sessionId: string) => void): void {
	onResync = handler;
}

/**
 * Ask for a fresh snapshot to be pushed to whoever is watching this session.
 *
 * [reload] does this by itself; this is for mutations that change *which* entries
 * are on the active branch without replacing the agent — branch navigation, where
 * the leaf moves backwards and the item list therefore shrinks. `add`/`patch` can
 * only express growth, so `hello` is the only honest answer.
 */
export function resync(sessionId: string): void {
	onResync(sessionId);
}

/**
 * Replace a stale session while keeping its subscribers attached.
 *
 * Tearing the agent down would otherwise drop every WebSocket listener with it,
 * and a client that was watching a run would simply stop receiving events with
 * no indication anything happened. Listeners move across to the new agent, and
 * because the new agent's sequence restarts at 0 every client's cursor is now
 * meaningless — so they are handed a full snapshot rather than left to fall
 * silently behind.
 */
async function reload(old: LiveAgent): Promise<LiveAgent> {
	const carried = [...old.listeners];
	// Clear first so destroy() cannot detach them.
	old.listeners.clear();
	await destroy(old.sessionId);

	const fresh = await create(old.sessionId);
	for (const listener of carried) fresh.listeners.add(listener);
	onResync(old.sessionId);
	return fresh;
}

/** Wire one loaded session: SDK events → translator → coalescer → subscribers. */
function attach(session: AgentSession, sessionId: string, path: string): LiveAgent {
	// The coalescer resolves the agent by id at flush time rather than closing
	// over it, so the pipeline can be built before the agent exists. `destroy()`
	// stops the coalescer, so a torn-down session can never flush into whatever
	// replaced it.
	const coalescer = createCoalescer((mutation) => {
		const current = agents.get(sessionId);
		if (current) publish(current, mutation);
	});
	const translator = createTranslator((mutation) => coalescer.push(mutation), {
		running: () => session.isStreaming,
		context: () => session.getContextUsage(),
	});

	const live: LiveAgent = {
		session,
		sessionId,
		path,
		unsubscribe: () => {},
		listeners: new Set(),
		seq: 0,
		touched: new Map(),
		view: translator,
		coalescer,
		touchedAt: Date.now(),
		rebuiltAt: 0,
		promptChain: Promise.resolve(),
		...readStat(path),
	};
	live.unsubscribe = session.subscribe((event) => consume(live, event));

	agents.set(sessionId, live);
	startSweeper();
	return live;
}

async function create(sessionId: string): Promise<LiveAgent> {
	const info = await requireLocated(sessionId);
	const sessionManager = SessionManager.open(info.path);

	// No `uiContext` is bound on purpose. The SDK then uses its own no-op
	// context, which makes `ctx.hasUI` false, so extensions take their
	// non-interactive path instead of opening a dialog nobody can answer.
	// Its dialog methods still resolve immediately (confirm→false,
	// select/input/editor→undefined), so a dialog can never hang a run.
	const { session } = await createAgentSession({
		cwd: info.cwd,
		sessionManager,
		modelRuntime: await getModelRuntime(),
	});

	const live = attach(session, sessionId, info.path);
	// Starting an AgentSession appends model/thinking entries of its own, and
	// that write may not have landed by the time the constructor returns.
	// Re-stat on the next tick so our own startup is not read as an external edit.
	setImmediate(() => Object.assign(live, readStat(info.path)));
	return live;
}

/** Create a brand-new session in `cwd` and return its id. */
export async function createSession(
	cwd: string,
	options: { modelId?: string; provider?: string; thinkingLevel?: string },
): Promise<string> {
	const runtime = await getModelRuntime();
	const model =
		options.provider && options.modelId ? runtime.getModel(options.provider, options.modelId) : undefined;
	if (options.provider && options.modelId && !model) {
		throw new HttpError(400, `Unknown model ${options.provider}/${options.modelId}`, "unknown_model");
	}

	const { session } = await createAgentSession({
		cwd,
		sessionManager: SessionManager.create(cwd),
		modelRuntime: runtime,
		...(model ? { model } : {}),
		...(options.thinkingLevel ? { thinkingLevel: options.thinkingLevel as ThinkingLevel } : {}),
	});

	const sessionId = session.sessionId;
	const path = session.sessionFile;
	if (!path) {
		session.dispose();
		throw new HttpError(500, "New session was not persisted", "not_persisted");
	}

	attach(session, sessionId, path);

	// The file does not exist until the first entry is appended, so publish a
	// placeholder that lookups and listings can see in the meantime.
	const now = new Date();
	registerSession({
		id: sessionId,
		path,
		cwd,
		created: now,
		modified: now,
		messageCount: 0,
		firstMessage: "",
	});
	return sessionId;
}

/**
 * Create the daily default workspace: `~/pi-cwd-YYYYMMDD` (server-local date).
 *
 * The directory is created once per day; later calls on the same day reuse it
 * and only start a new session inside. The client cannot influence the path at
 * all — no injection surface, and two devices always agree on "today".
 */
export async function createWorkspace(): Promise<{ id: string; cwd: string; created: boolean }> {
	const cwd = join(homedir(), `pi-cwd-${todayStamp()}`);
	let created = false;
	if (!existsSync(cwd)) {
		mkdirSync(cwd, { recursive: true });
		created = true;
	}
	const id = await createSession(cwd, {});
	return { id, cwd, created };
}

/** Server-local `pi-cwd-YYYYMMDD` suffix so all devices share "today". */
export function todayStamp(date = new Date()): string {
	const pad = (n: number) => String(n).padStart(2, "0");
	return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`;
}

/**
 * Run `fn` with exclusive access to a session's prompt admission.
 *
 * Without this, concurrent prompts all read `isStreaming === false` in the same
 * tick, all pass the busy check, and all call `session.prompt()`. Measured with
 * six simultaneous requests: every one came back `accepted`, but only the first
 * reached the conversation — the other five were silently dropped. Two phones
 * sending at once, or one impatient double-tap, would lose messages.
 *
 * The critical section covers only admission (the busy check plus the call up
 * to preflight), not the run itself, so a queued follow-up still returns
 * promptly instead of waiting out the whole turn.
 */
export function withPromptLock<T>(live: LiveAgent, fn: () => Promise<T>): Promise<T> {
	const result = live.promptChain.then(fn, fn);
	// Keep the chain alive regardless of outcome, or one rejection would wedge
	// every later prompt on this session.
	live.promptChain = result.then(
		() => undefined,
		() => undefined,
	);
	return result;
}

/**
 * One SDK event in; zero or more item mutations out, batched.
 *
 * Nothing invalidates the session caches from here, on purpose. They key on the
 * file's `(mtime, size)`, which an append moves, so the next reader notices by
 * itself — and re-reads only this one file. The previous code dropped the whole
 * snapshot on every appended entry, which put a 476ms rescan of all 135 sessions
 * behind the next request. See `sessions/scan.ts`.
 */
function consume(live: LiveAgent, event: AgentSessionEvent): void {
	live.touchedAt = Date.now();

	// Our own appends move the file; refresh the fingerprint so they are not
	// mistaken for an external writer. The stat is deferred to the next tick
	// because the SDK emits this event before the write has necessarily reached
	// disk — reading it synchronously captures the pre-write size and makes
	// every one of our own appends look like somebody else's.
	if (event.type === "entry_appended") {
		setImmediate(() => Object.assign(live, readStat(live.path)));
	} else if (event.type === "message_end") {
		// The SDK appends the message to the session manager right after
		// emitting message_end, but the only entry_appended events it emits are
		// for extension appendEntry() — a regular turn never produces one.
		// Without the authoritative entry there is nothing to reconcile the
		// streamed item against, so a conversation built from live pushes would
		// never get its usage, refs or truncation markers. Feed it back in on the
		// microtask that follows the append, when the entry exists to look up.
		const message = event.message;
		if (isPersistedMessage(message)) {
			queueMicrotask(() => {
				const entry = findEntryForMessage(live, message);
				if (entry) consume(live, { type: "entry_appended", entry });
			});
		}
	}

	live.view.handle(event);
}

/**
 * Publish the notice(s) for entries pi appended without an SDK event.
 *
 * `setModel()` / `setThinkingLevel()` write model_change / thinking_level_change
 * entries, and `compact()` writes a compaction entry, but none of the three
 * emits an AgentSessionEvent, and the whole live path is
 * event-driven — so the app would otherwise only see the notice on the next
 * resync, which in practice means the reload triggered by the next message
 * (that reload happens because our own append never refreshed the file
 * fingerprint, so the next write-acquire mistakes it for an external edit).
 * Feeding the entries through the normal entry_appended pipeline pushes the
 * notice now, and the fingerprint refresh in [consume] stops the reload.
 *
 * @param before The leaf entry when the caller started mutating; only entries
 *   appended after it are published.
 */
export function publishAppendedSince(live: LiveAgent, before: SessionEntry | undefined): void {
	const sm = live.session.sessionManager;
	const appended: SessionEntry[] = [];
	const seen = new Set<string>();
	let entry = sm.getLeafEntry();
	while (entry && entry.id !== before?.id && !seen.has(entry.id)) {
		seen.add(entry.id);
		if (entry.type === "model_change" || entry.type === "thinking_level_change" || entry.type === "compaction") {
			appended.push(entry);
		}
		entry = entry.parentId ? sm.getEntry(entry.parentId) : undefined;
	}
	// Oldest first, matching the order a hello would list them.
	for (const e of appended.reverse()) consume(live, { type: "entry_appended", entry: e });
}

export async function destroy(sessionId: string): Promise<void> {
	const live = agents.get(sessionId);
	if (!live) return;
	agents.delete(sessionId);
	live.listeners.clear();
	live.coalescer.stop();
	try {
		live.unsubscribe();
		live.session.dispose();
	} catch (err) {
		console.error(`[${sessionId}] dispose failed:`, err);
	}
}

export async function disposeAll(): Promise<void> {
	if (sweeper) clearInterval(sweeper);
	sweeper = undefined;
	await Promise.all([...agents.keys()].map(destroy));
}

/**
 * Roles the SDK persists via message_end (see AgentSession._handleAgentEvent).
 * Everything else — bashExecution, custom, compactionSummary, branchSummary —
 * is appended through other paths and stays out of this forwarding.
 */
function isPersistedMessage(message: { role?: string }): boolean {
	return message.role === "user" || message.role === "assistant" || message.role === "toolResult";
}

/**
 * The entry a just-persisted message landed in.
 *
 * appendMessage() stores the exact message object it was handed, so reference
 * equality is a reliable fingerprint. The leaf is the fast path — nothing else
 * appends between message_end and our microtask — with a walk of all entries
 * as a defensive fallback if that assumption ever breaks.
 */
function findEntryForMessage(live: LiveAgent, message: { role?: string }): SessionEntry | undefined {
	const sm = live.session.sessionManager;
	const leaf = sm.getLeafEntry();
	if (leaf?.type === "message" && leaf.message === message) return leaf;
	for (const entry of sm.getEntries()) {
		if (entry.type === "message" && entry.message === message) return entry;
	}
	return undefined;
}

function startSweeper(): void {
	if (sweeper) return;
	sweeper = setInterval(() => {
		const now = Date.now();
		for (const [id, live] of agents) {
			if (live.listeners.size > 0) continue;
			if (live.session.isStreaming) continue;
			if (now - live.touchedAt < IDLE_TIMEOUT_MS) continue;
			void destroy(id);
		}
	}, SWEEP_INTERVAL_MS);
	sweeper.unref();
}

function readStat(path: string): { knownSize: number; knownMtimeMs: number } {
	try {
		const st = statSync(path);
		return { knownSize: st.size, knownMtimeMs: st.mtimeMs };
	} catch {
		return { knownSize: -1, knownMtimeMs: -1 };
	}
}

function wasWrittenByOthers(live: LiveAgent): boolean {
	const current = readStat(live.path);
	return current.knownSize !== live.knownSize || current.knownMtimeMs !== live.knownMtimeMs;
}
