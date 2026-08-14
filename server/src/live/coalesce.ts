import type { Mutation } from "./types.ts";

/**
 * Batch mutations so one flush becomes one WebSocket frame per growing field.
 *
 * A fast model emits well over a hundred deltas a second, and the old wire sent
 * one frame each — 135 frames for a trivial "print 1 to 5" turn, thousands for a
 * real answer. Merging them here collapses that to roughly one frame per
 * [FLUSH_MS], and the frames that remain are big enough to be worth a radio
 * wake-up.
 *
 * The client used to do exactly this itself, on a 33ms timer, with a phase-
 * routing invariant to keep thinking deltas out of the text accumulator. Doing it
 * on the server deletes that code *and* the invariant: appends are merged only
 * when they target the same item and the same field, so a phase change simply
 * starts a new mutation.
 */

/** One flush per interval. Fast enough to read as live, slow enough to batch. */
export const FLUSH_MS = 80;

/** Flush early once a merged append gets this big, to bound frame size. */
export const FLUSH_BYTES = 4 * 1024;

export interface Coalescer {
	push(mutation: Mutation): void;
	/**
	 * Send everything buffered right now.
	 *
	 * Must be called before anything reads sequence numbers — building a `hello`,
	 * or handing a replay buffer to a new subscriber — or a queued append would
	 * be delivered *after* a snapshot that already contains its text, and the
	 * client would show it twice.
	 */
	flush(): void;
	stop(): void;
}

export function createCoalescer(send: (mutation: Mutation) => void): Coalescer {
	const queue: Mutation[] = [];
	let bytes = 0;
	let timer: NodeJS.Timeout | undefined;

	function flush(): void {
		if (timer) {
			clearTimeout(timer);
			timer = undefined;
		}
		if (queue.length === 0) return;
		// Splice before sending: a listener that throws must not leave the queue
		// full and get the same mutations again on the next flush.
		const batch = queue.splice(0, queue.length);
		bytes = 0;
		for (const mutation of batch) send(mutation);
	}

	function push(mutation: Mutation): void {
		const last = queue[queue.length - 1];
		if (
			mutation.t === "patch" &&
			mutation.append &&
			mutation.set === undefined &&
			last?.t === "patch" &&
			last.append &&
			last.set === undefined &&
			last.id === mutation.id &&
			last.append.f === mutation.append.f
		) {
			// Same item, same field, nothing in between: one frame will do.
			last.append.s += mutation.append.s;
		} else if (
			mutation.t === "patch" &&
			mutation.set &&
			mutation.append === undefined &&
			last?.t === "patch" &&
			last.set &&
			last.append === undefined &&
			last.id === mutation.id
		) {
			// Two field updates for the same item with nothing between them. Later
			// wins, which is what applying them in order would have done anyway —
			// a tool finishing emits `running:false` and then its result arrives.
			last.set = { ...last.set, ...mutation.set };
		} else {
			queue.push(mutation);
		}

		bytes += mutation.t === "patch" && mutation.append ? mutation.append.s.length : 0;
		if (bytes >= FLUSH_BYTES) {
			flush();
			return;
		}
		if (!timer) {
			timer = setTimeout(flush, FLUSH_MS);
			// A pending flush must not hold the process open at shutdown.
			timer.unref();
		}
	}

	return {
		push,
		flush,
		stop: () => {
			if (timer) clearTimeout(timer);
			timer = undefined;
			queue.length = 0;
			bytes = 0;
		},
	};
}
