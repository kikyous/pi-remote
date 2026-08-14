import { statSync } from "node:fs";

import { type SessionEntry, SessionManager } from "@earendil-works/pi-coding-agent";

import { itemsFromEntries } from "./items.ts";
import type { Item } from "../../protocol.ts";
import type { Located } from "./scan.ts";

/**
 * The slice of `SessionManager` this module reads.
 *
 * Structural on purpose: the SDK declares `ReadonlySessionManager` but does not
 * re-export it from the package root, and three methods is a smaller contract
 * to depend on anyway.
 */
export interface EntryTree {
	buildContextEntries(): SessionEntry[];
	getLeafId(): string | null;
	getEntry(id: string): SessionEntry | undefined;
}

/**
 * The expensive half of the read path: the parsed session tree.
 *
 * `SessionManager.open()` reads and parses the whole file eagerly — 52ms for the
 * largest local session (7.7MB / 1460 entries). The previous code opened it
 * fresh per request, so a single session-open cost **three** full parses
 * (`getSessionDetail` parsed once for its settings and again inside
 * `estimateSessionTokens`, then `/entries` parsed a third time), and paging back
 * through a long session cost one parse per page.
 *
 * A session file is append-only, so `(mtimeMs, size)` is a complete cache key:
 * a hit is provably current, and any writer — us or an external pi TUI — moves
 * it. That is the whole invalidation story; there is no TTL to tune.
 */

export interface SessionModel {
	/**
	 * The active branch with compaction applied — what the user is looking at.
	 *
	 * Not `getEntries()`: a session is a tree, and that would also hand back
	 * abandoned branches.
	 */
	entries: SessionEntry[];
	leafId: string | null;
	/** Raw (un-shortened) entry by id, for `/full`. */
	entry(id: string): SessionEntry | undefined;
	/**
	 * The whole branch as items, built once per file version.
	 *
	 * Deliberately the *whole* branch rather than per page: a tool call and its
	 * result are separate entries, so pairing them page by page would leave a call
	 * whose result sits in a newer page stuck showing "running" forever. That is
	 * the bug the client used to work around by re-linking its entire loaded list
	 * after every change.
	 */
	items(): Item[];
}

/**
 * Cap on retained entries across all cached sessions.
 *
 * Bounds memory by the thing that actually costs it. A session-count cap would
 * let six copies of the 7.7MB session sit resident; this lets many small ones
 * or a couple of large ones, which is the shape real use has.
 */
const MAX_CACHED_ENTRIES = 25_000;

interface CacheEntry {
	stamp: string;
	model: SessionModel;
	entryCount: number;
}

/** Insertion-ordered, so the least recently used key is the first to evict. */
const cache = new Map<string, CacheEntry>();
let retained = 0;

/**
 * Lets a loaded agent hand over its in-memory tree.
 *
 * Injected rather than imported so this module does not depend on the agent
 * pool — the same reason `store.ts` takes a running probe. A live agent's tree
 * is authoritative: it may hold appends that have not reached disk yet, so
 * reading the file behind its back would serve a view one entry short.
 */
let liveSource: (sessionId: string) => EntryTree | undefined = () => undefined;

export function setLiveSource(source: (sessionId: string) => EntryTree | undefined): void {
	liveSource = source;
}

/**
 * The parsed model for a session, or undefined when its file does not exist yet
 * (a session created through `POST /sessions` has none until its first append).
 */
export function getModel(located: Located): SessionModel | undefined {
	const live = liveSource(located.id);
	// Not cached: an agent mutates its tree in place, so there is no stamp that
	// would stay valid. Rebuilding is ~1ms — `buildContextEntries()` walks an
	// already-parsed array.
	if (live) return fromManager(live);

	const stamp = stampOf(located.path);
	if (stamp === undefined) return undefined;

	const hit = cache.get(located.path);
	if (hit?.stamp === stamp) {
		// Re-insert to mark it most recently used.
		cache.delete(located.path);
		cache.set(located.path, hit);
		return hit.model;
	}

	const model = fromManager(SessionManager.open(located.path));
	store(located.path, stamp, model);
	return model;
}

/** Forget a session's parse. Call after deleting its file. */
export function forgetModel(path: string): void {
	const hit = cache.get(path);
	if (!hit) return;
	retained -= hit.entryCount;
	cache.delete(path);
}

function fromManager(sm: EntryTree): SessionModel {
	const entries = sm.buildContextEntries();
	let items: Item[] | undefined;
	return {
		entries,
		leafId: sm.getLeafId(),
		entry: (id) => sm.getEntry(id),
		items: () => (items ??= itemsFromEntries(entries)),
	};
}

function store(path: string, stamp: string, model: SessionModel): void {
	const previous = cache.get(path);
	if (previous) retained -= previous.entryCount;
	cache.delete(path);

	const entryCount = model.entries.length;
	cache.set(path, { stamp, model, entryCount });
	retained += entryCount;

	for (const key of evictionPlan([...cache].map(([p, hit]) => [p, hit.entryCount]), path, retained, MAX_CACHED_ENTRIES)) {
		retained -= cache.get(key)?.entryCount ?? 0;
		cache.delete(key);
	}
}

/**
 * Which cached paths to drop so the retained entry count fits the budget.
 *
 * `order` is least-recently-used first. Exported so the two guards can be
 * tested without fabricating a 25,000-entry session: the path just added is
 * never evicted, and one session bigger than the entire budget stays servable
 * rather than being dropped the instant it is parsed.
 */
export function evictionPlan(
	order: Array<[path: string, entryCount: number]>,
	keep: string,
	retained: number,
	budget: number,
): string[] {
	const drop: string[] = [];
	let left = retained;
	for (const [path, entryCount] of order) {
		if (left <= budget) break;
		if (path === keep) continue;
		drop.push(path);
		left -= entryCount;
	}
	return drop;
}

function stampOf(path: string): string | undefined {
	try {
		const st = statSync(path);
		return `${st.mtimeMs}:${st.size}`;
	} catch {
		return undefined;
	}
}
