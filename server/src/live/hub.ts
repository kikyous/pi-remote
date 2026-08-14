import type { Item, Push, SessionStatus } from "../protocol.ts";
import type { Coalescer } from "./coalesce.ts";
import type { LiveView, Mutation } from "./types.ts";

/**
 * Sequence assignment, subscriber fan-out and catch-up bookkeeping.
 *
 * Extracted from the pi agent pool because none of it is about pi: a session
 * that is loaded, whatever loaded it, numbers its mutations, remembers which
 * item each number touched, and hands both to whoever is watching. A backend
 * supplies the [LiveView] and the [Coalescer]; everything below is shared.
 */

/** A mutation with its sequence assigned, as it went out. */
export interface BufferedPush {
	seq: number;
	push: Push;
}

export type EventListener = (buffered: BufferedPush) => void;

/**
 * The backend-independent half of a loaded session.
 *
 * A backend's own record extends this with whatever it needs to drive its
 * agent — for pi that is the `AgentSession`, the file fingerprint and the
 * prompt lock.
 */
export interface LiveSession {
	sessionId: string;
	listeners: Set<EventListener>;
	seq: number;
	/**
	 * itemId → the sequence at which it last changed.
	 *
	 * This is what a reconnecting client is caught up from, and it replaced a ring
	 * buffer of the last 200 pushes. That buffer could not do the job: a 2000-word
	 * answer streams **1042 pushes**, so any reconnect during a long turn fell off
	 * the end and got a full snapshot instead. One number per item covers a whole
	 * session for less memory than 200 retained payloads, and resending an item
	 * whole is exact by construction — no delta arithmetic to get subtly wrong.
	 */
	touched: Map<string, number>;
	/** Agent events → item mutations, and the in-flight view of them. */
	view: LiveView;
	/** Batches streaming appends into one frame per flush interval. */
	coalescer: Coalescer;
	/** Last time anything touched this session; drives idle disposal. */
	touchedAt: number;
}

/** Assign a sequence, note which item moved, fan out. */
export function publish(live: LiveSession, mutation: Mutation): void {
	const buffered: BufferedPush = {
		seq: ++live.seq,
		push: { ...mutation, sessionId: live.sessionId, seq: live.seq } as Push,
	};

	if (mutation.t === "add") live.touched.set(mutation.item.id, live.seq);
	else if (mutation.t === "patch") live.touched.set(mutation.id, live.seq);

	for (const listener of live.listeners) {
		try {
			listener(buffered);
		} catch (err) {
			console.error(`[${live.sessionId}] listener failed:`, err);
		}
	}
}

/**
 * Flush buffered appends, then report the position and the in-flight item.
 *
 * Callers building a snapshot must go through here: a queued append delivered
 * *after* a snapshot that already contains its text would show up twice on the
 * client. Flushing first makes the returned `seq` the true high-water mark.
 */
export function snapshotPoint(live: LiveSession): { seq: number; tail: Item | undefined; status: SessionStatus } {
	live.coalescer.flush();
	// A subscriber wants the context bar filled, so this is one of the few places
	// worth paying for the estimate.
	live.view.syncStatus(true);
	live.coalescer.flush();
	return { seq: live.seq, tail: live.view.tail(), status: live.view.status() };
}

/**
 * What a client resuming from `sinceSeq` needs, expressed as item ids.
 *
 * `undefined` means "no incremental catch-up is possible, send a snapshot":
 *   - no cursor at all — a fresh subscribe;
 *   - a cursor *ahead* of us, which means the session was dropped and rebuilt,
 *     so the number means nothing and anything that changed while it was gone
 *     is invisible.
 *
 * Otherwise: every item that changed after `sinceSeq`. The caller resends each
 * one whole, which is exact whatever happened to it — several appends, a
 * `set`, or both. There is deliberately no attempt to replay the individual
 * mutations: that is what the ring buffer did, and reconstructing "the last
 * 1042 pushes" is both bigger and easier to get wrong than "these three items
 * now look like this".
 *
 * Exported to be tested without a live agent — the interesting cases (a long
 * turn, a restarted agent, an already-current client) are hard to provoke
 * against a real model.
 */
export function catchUpIds(
	touched: ReadonlyMap<string, number>,
	currentSeq: number,
	sinceSeq: number | undefined,
): Set<string> | undefined {
	if (sinceSeq === undefined) return undefined;
	if (sinceSeq > currentSeq) return undefined;

	const stale = new Set<string>();
	for (const [id, seq] of touched) if (seq > sinceSeq) stale.add(id);
	return stale;
}

/** Attach a listener and report what it has to be caught up on. */
export function subscribe(
	live: LiveSession,
	listener: EventListener,
	sinceSeq: number | undefined,
): Set<string> | undefined {
	live.listeners.add(listener);
	live.touchedAt = Date.now();
	return catchUpIds(live.touched, live.seq, sinceSeq);
}

export function unsubscribe(live: LiveSession, listener: EventListener): void {
	live.listeners.delete(listener);
	live.touchedAt = Date.now();
}
