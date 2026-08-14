import assert from "node:assert/strict";
import { test } from "node:test";

import { todayStamp } from "./agent-pool.ts";

test("todayStamp pads day and month to two digits", () => {
	assert.equal(todayStamp(new Date(2026, 0, 5)), "20260105");
	assert.equal(todayStamp(new Date(2026, 10, 30)), "20261130");
});
