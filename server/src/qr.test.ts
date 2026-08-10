import assert from "node:assert/strict";
import { test } from "node:test";

import { buildConnectPayload, CONNECT_SCHEME, renderConnectQr } from "./qr.ts";

test("payload carries url and token as query params", () => {
	const payload = buildConnectPayload("http://192.168.1.10:30150", "tok_en-123");
	assert.equal(
		payload,
		"piremote://connect?url=http%3A%2F%2F192.168.1.10%3A30150&token=tok_en-123",
		"url is percent-encoded so the scheme stays parseable",
	);
});

test("payload round-trips through URL parsing", () => {
	const url = "http://192.168.31.117:30150";
	const token = "85Ou5U44v-lN0BckrE6QJ5OuMgBAekZQ";
	const uri = new URL(buildConnectPayload(url, token));
	assert.equal(uri.protocol, "piremote:");
	assert.equal(uri.hostname, "connect");
	assert.equal(uri.searchParams.get("url"), url);
	assert.equal(uri.searchParams.get("token"), token);
});

test("renderConnectQr prints a scannable block for a real payload", async () => {
	const payload = buildConnectPayload("http://192.168.31.117:30150", "85Ou5U44v-lN0BckrE6QJ5OuMgBAekZQ");
	const qr = await renderConnectQr(payload);
	assert.ok(qr.length > 200, "QR should be a substantial multi-line block");
	assert.ok(!qr.includes(CONNECT_SCHEME), "QR is rendered as modules, not plaintext");
	assert.match(qr, /\n/, "multi-line");
});
