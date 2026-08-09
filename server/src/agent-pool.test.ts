import assert from "node:assert/strict";
import { test } from "node:test";

import { type BufferedEvent, computeReplay, todayStamp } from "./agent-pool.ts";

/** A buffer holding seqs `from..to`, as the ring buffer would after eviction. */
function buffer(from: number, to: number): BufferedEvent[] {
	const out: BufferedEvent[] = [];
	for (let seq = from; seq <= to; seq++) out.push({ seq, event: { type: "message_update" } });
	return out;
}

test("a fresh subscriber gets live events only", () => {
	const { replay, gap } = computeReplay(buffer(1, 10), 10, undefined);

	assert.deepEqual(replay, []);
	assert.equal(gap, false);
});

test("replays exactly the events after the client's cursor", () => {
	const { replay, gap } = computeReplay(buffer(1, 12), 12, 4);

	assert.equal(gap, false);
	assert.deepEqual(
		replay.map((b) => b.seq),
		[5, 6, 7, 8, 9, 10, 11, 12],
		"starts one past the cursor, no repeats",
	);
});

test("a client that is already current gets nothing", () => {
	const { replay, gap } = computeReplay(buffer(1, 10), 10, 10);

	assert.deepEqual(replay, []);
	assert.equal(gap, false, "being current is not a gap");
});

test("reports a gap when the missed events have been evicted", () => {
	// Buffer starts at 50; the client last saw 10, so 11..49 are gone.
	const { replay, gap } = computeReplay(buffer(50, 100), 100, 10);

	assert.deepEqual(replay, [], "no partial replay — it would look like a complete catch-up");
	assert.equal(gap, true);
});

test("the boundary case, cursor exactly one before the buffer, is not a gap", () => {
	const { replay, gap } = computeReplay(buffer(50, 100), 100, 49);

	assert.equal(gap, false);
	assert.equal(replay[0]?.seq, 50, "the whole buffer is still contiguous with the cursor");
});

test("an empty buffer with a cursor is a gap, not a silent no-op", () => {
	// Happens when the agent was disposed and reloaded: its buffer is empty but
	// the client still holds a sequence from the previous incarnation.
	const { replay, gap } = computeReplay([], 0, 7);

	assert.deepEqual(replay, []);
	// currentSeq 0 < sinceSeq 7 → the client is ahead of a restarted agent.
	assert.equal(gap, false, "a restarted agent is not a gap; the client simply has nothing to catch up on");
});

test("an empty buffer behind the client's cursor reports a gap", () => {
	const { replay, gap } = computeReplay([], 20, 7);

	assert.deepEqual(replay, []);
	assert.equal(gap, true);
});

test("todayStamp pads day and month to two digits", () => {
	const d = new Date(2026, 7, 9); // Aug 9
	assert.equal(todayStamp(d), "20260809");
});

test("todayStamp uses the server-local date", () => {
	const d = new Date(2026, 0, 3); // Jan 3
	assert.equal(todayStamp(d), "20260103");
});
