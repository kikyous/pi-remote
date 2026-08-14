import type { ContextUsageDto, Item, ItemPatch, SessionStatus } from "../protocol.ts";

/**
 * The vocabulary the live path speaks, independent of which agent produced it.
 *
 * A backend's translator turns whatever its agent emits into [Mutation]s; from
 * there the coalescer, the hub and the WebSocket deal only in items. That
 * boundary is what lets a second agent be added without the fan-out, the
 * catch-up bookkeeping or the client learning anything about it.
 */

/** A push without its `sessionId`/`seq`, which the hub assigns. */
export type Mutation =
	| { t: "add"; item: Item }
	| { t: "patch"; id: string; append?: { f: "text" | "thinking" | "output"; s: string }; set?: ItemPatch }
	| { t: "status"; status: SessionStatus };

/**
 * What a translator cannot know by itself, supplied by its backend.
 *
 * Split in two because the halves cost wildly different amounts. `running` is a
 * field read. `context` may walk the whole branch and re-estimate every message
 * — measured at **1.96ms** on a 1460-entry pi session, which at one call per
 * event came to **15.6 seconds of CPU for a single long turn**, on the event
 * path, ahead of every flush. Deltas cannot change stored context anyway, so it
 * is only read when something has actually landed.
 */
export interface StatusProbe {
	running(): boolean;
	context(): ContextUsageDto | undefined;
}

/**
 * The half of a translator the hub and `ws.ts` use.
 *
 * A backend's own translator interface extends this with its `handle(event)`,
 * whose argument type is the only agent-specific thing about it.
 */
export interface LiveView {
	/** The in-flight assistant item, for a client subscribing mid-run. */
	tail(): Item | undefined;
	status(): SessionStatus;
	/** Re-read the probe and emit a status push if anything moved. */
	syncStatus(withContext?: boolean): void;
	/**
	 * The id the server's item list uses for an id the client may still hold.
	 *
	 * A streaming message may be added under a minted id and keep it on the
	 * client for good, while the settled entry has an id of its own. Without
	 * this, catching a client up on that message would look like an id that
	 * resolves to nothing. A backend whose streamed and stored ids already agree
	 * returns the id unchanged.
	 */
	resolve(id: string): string;
}
