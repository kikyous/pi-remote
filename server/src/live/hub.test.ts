import assert from "node:assert/strict";
import { test } from "node:test";

import { catchUpIds, markRebuilt, type LiveSession } from "./hub.ts";

/** itemId → the sequence at which it last changed, as the hub records it. */
function touched(entries: Record<string, number>): Map<string, number> {
	return new Map(Object.entries(entries));
}

test("a fresh subscriber gets a snapshot, not a catch-up", () => {
	assert.equal(catchUpIds(touched({ a: 1, b: 2 }), 2, undefined), undefined);
});

test("only the items that changed after the cursor are named", () => {
	const stale = catchUpIds(touched({ old: 3, mid: 7, fresh: 11 }), 11, 7);

	assert.deepEqual([...(stale ?? [])], ["fresh"], "mid changed exactly at the cursor, so it is already there");
});

test("a client already current is told about nothing", () => {
	const stale = catchUpIds(touched({ a: 1, b: 5 }), 5, 5);

	assert.deepEqual([...(stale ?? [])], []);
});

test("a cursor ahead of the agent asks for a snapshot", () => {
	// The agent was idle-disposed and rebuilt, so its sequence restarted at 0 while
	// the client still holds one from the previous incarnation. Treating that as
	// "nothing to catch up on" would leave the screen showing history from before
	// the gap, with no way to learn that an external writer changed the session.
	assert.equal(catchUpIds(touched({}), 0, 7), undefined);
});

test("a cursor from before a rebuild asks for a snapshot", () => {
	// Branch navigation moved the leaf backwards. The client holds a cursor from
	// the branch it was reading, and `touched` names nothing new — so without the
	// mark this resolves to "you are current" and the screen keeps showing rows
	// that are no longer on the active branch.
	assert.equal(catchUpIds(touched({ a: 1, b: 2 }), 3, 2, 3), undefined);
});

test("a cursor issued after a rebuild is caught up normally", () => {
	// The snapshot taken at the rebuild reports its own sequence, so a client that
	// arrived afterwards is at or above the mark and needs no second snapshot.
	const stale = catchUpIds(touched({ a: 1, fresh: 4 }), 4, 3, 3);

	assert.deepEqual([...(stale ?? [])], ["fresh"]);
});

test("markRebuilt consumes a sequence, so the mark and older cursors cannot collide", () => {
	const live = { seq: 7, rebuiltAt: 0 } as LiveSession;
	markRebuilt(live);

	assert.equal(live.seq, 8);
	assert.equal(live.rebuiltAt, 8);
	// The cursor of a client that had seen everything up to the rebuild.
	assert.equal(catchUpIds(touched({}), live.seq, 7, live.rebuiltAt), undefined);
});

test("a long turn is one item to resend, however many pushes it took", () => {
	// The measured case this replaced a ring buffer for: a 2000-word answer streams
	// 1042 pushes. All of them touch the same item, so catching up costs one.
	const streaming = new Map<string, number>();
	for (let seq = 1; seq <= 1042; seq++) streaming.set("live-1", seq);

	const stale = catchUpIds(streaming, 1042, 3);
	assert.deepEqual([...(stale ?? [])], ["live-1"]);
});
