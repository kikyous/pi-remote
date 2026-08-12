/**
 * Capture a real push stream as a fixture.
 *
 *   node test/capture.mjs ../android/app/src/test/resources/pushes.jsonl
 *
 * `protocol.ts` and `net/Protocol.kt` are mirrored by hand, so the drift worth
 * guarding against is a renamed field that both sides still compile and that only
 * shows up as a frame the app quietly drops. `RealPushStreamTest` replays what this
 * writes; re-run it after any change to the wire types.
 *
 * Needs a running server (`npm start`) and costs one small model turn.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";

const TOKEN = readFileSync(join(homedir(), ".pi", "remote", "token"), "utf8").trim();
const BASE = "http://127.0.0.1:30150/api/v1";
const CWD = join(homedir(), "pi-remote-e2e");
const OUT = process.argv[2];

if (!OUT) {
	console.error("usage: node test/capture.mjs <output.jsonl>");
	process.exit(1);
}
mkdirSync(CWD, { recursive: true });

const api = async (path, init = {}) => {
	const res = await fetch(BASE + path, {
		...init,
		headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json", ...init.headers },
	});
	const text = await res.text();
	if (!res.ok) throw new Error(`${path} → ${res.status} ${text}`);
	return JSON.parse(text);
};

const { id } = await api("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });

const frames = [];
const ws = new WebSocket(`ws://127.0.0.1:30150/ws?token=${encodeURIComponent(TOKEN)}`);
let done;
const finished = new Promise((resolve) => (done = resolve));

ws.on("message", (raw) => {
	const text = raw.toString();
	frames.push(text);
	const push = JSON.parse(text);
	// The run is over once status goes quiet, but only after real content arrived.
	if (push.t === "status" && push.status.running === false && frames.length > 3) done();
});
await new Promise((resolve) => ws.once("open", resolve));
ws.send(JSON.stringify({ op: "subscribe", sessionId: id }));
await new Promise((resolve) => setTimeout(resolve, 300));

// A turn that exercises every item kind the wire has: thinking, a tool call with
// streamed output, and a text answer with usage.
await api(`/sessions/${id}/prompt`, {
	method: "POST",
	body: JSON.stringify({ message: "用 bash 输出 1 到 3，然后一句话总结" }),
});

const timer = setTimeout(done, 90_000);
await finished;
clearTimeout(timer);
ws.close();

writeFileSync(OUT, `${frames.join("\n")}\n`);

const kinds = frames.map((f) => JSON.parse(f).t).reduce((acc, t) => ((acc[t] = (acc[t] ?? 0) + 1), acc), {});
const bytes = frames.reduce((sum, f) => sum + Buffer.byteLength(f), 0);
console.log(`抓到 ${frames.length} 帧 / ${bytes} 字节 → ${OUT}`);
console.log("帧类型:", JSON.stringify(kinds));
const page = await api(`/sessions/${id}/items`);
console.log("落盘 items:", page.items.map((i) => i.kind).join(" → "));
process.exit(0);
