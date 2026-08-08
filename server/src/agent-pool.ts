import { statSync } from "node:fs";

import {
	type AgentSession,
	type AgentSessionEvent,
	createAgentSession,
	ModelRuntime,
	type SessionEntry,
	SessionManager,
} from "@earendil-works/pi-coding-agent";

import { HttpError } from "./http.ts";
import type { ThinkingLevel } from "./protocol.ts";
import { slimEvent } from "./slim.ts";
import { findSession, invalidateSessionCache, registerSession } from "./store.ts";

/**
 * Owns the live `AgentSession` objects.
 *
 * Browsing history never comes through here — that reads JSONL directly. An
 * agent is created only when someone actually prompts, and is disposed once
 * nobody is watching, so switching between sessions stays free.
 */

/** How long a session with no subscribers and no active run stays loaded. */
const IDLE_TIMEOUT_MS = 10 * 60 * 1000;
const SWEEP_INTERVAL_MS = 60 * 1000;

/** Events retained per session so a reconnecting client can catch up. */
const REPLAY_BUFFER_SIZE = 200;

export interface BufferedEvent {
	seq: number;
	/** Entry id when the event appended one — the cursor a client resumes from. */
	entryId?: string;
	event: unknown;
}

export type EventListener = (buffered: BufferedEvent) => void;

interface LiveAgent {
	session: AgentSession;
	sessionId: string;
	path: string;
	unsubscribe: () => void;
	listeners: Set<EventListener>;
	buffer: BufferedEvent[];
	seq: number;
	/** Last time anything touched this session; drives idle disposal. */
	touchedAt: number;
	/** Serializes prompt admission. See `withPromptLock`. */
	promptChain: Promise<unknown>;
	/** File identity as we last knew it, to notice writes we did not make. */
	knownSize: number;
	knownMtimeMs: number;
}

const agents = new Map<string, LiveAgent>();
const starting = new Map<string, Promise<LiveAgent>>();

let modelRuntime: ModelRuntime | undefined;
let sweeper: NodeJS.Timeout | undefined;

async function getModelRuntime(): Promise<ModelRuntime> {
	modelRuntime ??= await ModelRuntime.create();
	return modelRuntime;
}

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
 * Replace a stale session while keeping its subscribers attached.
 *
 * Tearing the agent down would otherwise drop every WebSocket listener with it,
 * and a client that was watching a run would simply stop receiving events with
 * no indication anything happened. Listeners move across to the new agent and
 * are told the sequence restarted so they can refetch.
 */
async function reload(old: LiveAgent): Promise<LiveAgent> {
	const carried = [...old.listeners];
	// Clear first so destroy() cannot detach them.
	old.listeners.clear();
	await destroy(old.sessionId);

	const fresh = await create(old.sessionId);
	for (const listener of carried) fresh.listeners.add(listener);

	// The new agent starts at seq 0, so every client's cursor is meaningless.
	// Tell them explicitly rather than letting them silently fall behind.
	const notice: BufferedEvent = { seq: ++fresh.seq, event: { type: "session_reloaded" } };
	fresh.buffer.push(notice);
	for (const listener of carried) {
		try {
			listener(notice);
		} catch (err) {
			console.error(`[${old.sessionId}] listener failed on reload:`, err);
		}
	}
	return fresh;
}

async function create(sessionId: string): Promise<LiveAgent> {
	const info = await findSession(sessionId);
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

	const live: LiveAgent = {
		session,
		sessionId,
		path: info.path,
		unsubscribe: () => {},
		listeners: new Set(),
		buffer: [],
		seq: 0,
		touchedAt: Date.now(),
		promptChain: Promise.resolve(),
		...readStat(info.path),
	};

	live.unsubscribe = session.subscribe((event) => publish(live, event));
	agents.set(sessionId, live);
	// Starting an AgentSession appends model/thinking entries of its own, and
	// that write may not have landed by the time the constructor returns.
	// Re-stat on the next tick so our own startup is not read as an external edit.
	setImmediate(() => Object.assign(live, readStat(info.path)));
	startSweeper();
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

	const live: LiveAgent = {
		session,
		sessionId,
		path,
		unsubscribe: () => {},
		listeners: new Set(),
		buffer: [],
		seq: 0,
		touchedAt: Date.now(),
		promptChain: Promise.resolve(),
		...readStat(path),
	};
	live.unsubscribe = session.subscribe((event) => publish(live, event));
	agents.set(sessionId, live);

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
		allMessagesText: "",
	});
	invalidateSessionCache();
	startSweeper();
	return sessionId;
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

function publish(live: LiveAgent, event: AgentSessionEvent): void {
	live.touchedAt = Date.now();

	// Our own appends move the file; refresh the fingerprint so they are not
	// mistaken for an external writer. The stat is deferred to the next tick
	// because the SDK emits this event before the write has necessarily reached
	// disk — reading it synchronously captures the pre-write size and makes
	// every one of our own appends look like somebody else's.
	if (event.type === "entry_appended") {
		setImmediate(() => Object.assign(live, readStat(live.path)));
		invalidateSessionCache();
	} else if (event.type === "message_end") {
		// The SDK appends the message to the session manager right after
		// emitting message_end, but the only entry_appended events it emits are
		// for extension appendEntry() — a regular turn never produces one.
		// Without the authoritative entry the client has nothing to render once
		// the streaming bubble clears, so a conversation built from live events
		// stays empty until a manual refetch. Forward it on the microtask that
		// follows the append, when the entry exists to look up.
		invalidateSessionCache();
		const message = event.message;
		if (isPersistedMessage(message)) {
			queueMicrotask(() => {
				const entry = findEntryForMessage(live, message);
				if (entry) publish(live, { type: "entry_appended", entry });
			});
		}
	}

	const buffered: BufferedEvent = {
		seq: ++live.seq,
		event: slimEvent(event),
		...(event.type === "entry_appended" ? { entryId: event.entry.id } : {}),
	};

	live.buffer.push(buffered);
	if (live.buffer.length > REPLAY_BUFFER_SIZE) live.buffer.shift();

	for (const listener of live.listeners) {
		try {
			listener(buffered);
		} catch (err) {
			console.error(`[${live.sessionId}] listener failed:`, err);
		}
	}
}

/**
 * Work out what a reconnecting client missed.
 *
 * Split out from `subscribe` so it can be tested without a live agent: the
 * cases that matter (an evicted gap, an already-current client, a partial
 * catch-up) are hard to provoke reliably against a real model.
 */
export function computeReplay(
	buffer: readonly BufferedEvent[],
	currentSeq: number,
	sinceSeq: number | undefined,
): { replay: BufferedEvent[]; gap: boolean } {
	// A fresh subscriber wants live events only.
	if (sinceSeq === undefined) return { replay: [], gap: false };
	// Already current — or ahead, which means the agent was reloaded and its
	// sequence restarted; either way there is nothing to replay.
	if (sinceSeq >= currentSeq) return { replay: [], gap: false };

	const oldest = buffer[0]?.seq;
	if (oldest === undefined) return { replay: [], gap: true };
	// The events between `sinceSeq` and the buffer start have been evicted.
	if (sinceSeq < oldest - 1) return { replay: [], gap: true };

	return { replay: buffer.filter((b) => b.seq > sinceSeq), gap: false };
}

/**
 * Attach a listener, replaying anything it missed since `sinceSeq`.
 *
 * Returns the events to send before live ones, plus whether the gap was too
 * large to cover — in which case the client should refetch the page instead.
 */
export function subscribe(
	live: LiveAgent,
	listener: EventListener,
	sinceSeq: number | undefined,
): { replay: BufferedEvent[]; gap: boolean } {
	live.listeners.add(listener);
	live.touchedAt = Date.now();
	return computeReplay(live.buffer, live.seq, sinceSeq);
}

export function unsubscribe(live: LiveAgent, listener: EventListener): void {
	live.listeners.delete(listener);
	live.touchedAt = Date.now();
}

export async function destroy(sessionId: string): Promise<void> {
	const live = agents.get(sessionId);
	if (!live) return;
	agents.delete(sessionId);
	live.listeners.clear();
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
