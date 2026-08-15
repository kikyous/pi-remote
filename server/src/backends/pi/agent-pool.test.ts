import assert from "node:assert/strict";
import { test } from "node:test";

import type { SessionEntry } from "@earendil-works/pi-coding-agent";

import type { LiveAgent } from "./agent-pool.ts";
import { publishAppendedSince, todayStamp } from "./agent-pool.ts";

test("todayStamp pads day and month to two digits", () => {
	assert.equal(todayStamp(new Date(2026, 0, 5)), "20260105");
	assert.equal(todayStamp(new Date(2026, 10, 30)), "20261130");
});

/**
 * A chain of entries, newest last, wearing just enough of a session manager for
 * [publishAppendedSince] to walk it.
 */
function fakeAgent(chain: Array<{ id: string; type: string }>): {
	live: LiveAgent;
	handled: string[];
} {
	const entries = chain.map((e, i) => ({
		...e,
		...(i > 0 ? { parentId: chain[i - 1]!.id } : {}),
	})) as unknown as SessionEntry[];
	const byId = new Map(entries.map((e) => [(e as { id: string }).id, e]));
	const handled: string[] = [];

	const live = {
		path: "/nonexistent",
		touchedAt: 0,
		session: {
			sessionManager: {
				getLeafEntry: () => entries[entries.length - 1],
				getEntry: (id: string) => byId.get(id),
			},
		},
		view: {
			handle: (event: { type: string; entry?: { id: string } }) => {
				if (event.entry) handled.push(event.entry.id);
			},
		},
	} as unknown as LiveAgent;

	return { live, handled };
}

test("appends published by hand cover exactly what pi persists without an event", () => {
	const { live, handled } = fakeAgent([
		{ id: "leaf-before", type: "message" },
		{ id: "m1", type: "model_change" },
		{ id: "msg", type: "message" },
		{ id: "t1", type: "thinking_level_change" },
		{ id: "c1", type: "compaction" },
	]);

	publishAppendedSince(live, { id: "leaf-before" } as unknown as SessionEntry);

	// The message is left out: it reaches subscribers through message_end. Order
	// is oldest first, matching how a hello would list them.
	assert.deepEqual(handled, ["m1", "t1", "c1"]);
});

test("nothing is published when the leaf has not moved", () => {
	const { live, handled } = fakeAgent([
		{ id: "a", type: "message" },
		{ id: "b", type: "compaction" },
	]);

	publishAppendedSince(live, { id: "b" } as unknown as SessionEntry);

	assert.deepEqual(handled, []);
});
