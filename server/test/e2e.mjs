/**
 * End-to-end smoke test against a running server.
 *   node e2e.mjs [prompt]
 */
import { readFileSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";

const TOKEN = readFileSync(join(homedir(), ".pi", "remote", "token"), "utf8").trim();
const BASE = "http://127.0.0.1:30150/api/v1";
const CWD = join(homedir(), "pi-remote-e2e");
mkdirSync(CWD, { recursive: true });

const api = async (path, init = {}) => {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json", ...init.headers },
  });
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  if (!res.ok) throw new Error(`${init.method ?? "GET"} ${path} → ${res.status} ${JSON.stringify(body)}`);
  return body;
};

const prompt = process.argv[2] ?? "回复恰好一个词：ok";

console.log("1. 新建会话 …");
const { id } = await api("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });
console.log("   sessionId:", id);

console.log("2. 协议版本 …");
const ping = await api("/ping");
if (ping.protocol !== 2) { console.error(`   失败: protocol=${ping.protocol}, 期望 2`); process.exit(1); }
console.log(`   version=${ping.version} protocol=${ping.protocol}`);

console.log("3. WS 订阅（hello 带首屏 + detail + status，无需 GET detail）…");
const ws = new WebSocket(`ws://127.0.0.1:30150/ws?token=${encodeURIComponent(TOKEN)}`);
const frames = [];
let bytes = 0;
let lastSeq = 0;
let hello;
let settled;
const settledPromise = new Promise((r) => (settled = r));

// The item list, folded from the push stream exactly as the client does it.
const items = [];
const applyText = (target, key, patch) => {
  const current = target[key];
  target[key] = { s: patch.s ?? current?.s ?? "", ...(patch.more ?? current?.more ? { more: patch.more ?? current?.more } : {}) };
};

ws.on("open", () => ws.send(JSON.stringify({ op: "subscribe", sessionId: id })));
ws.on("message", (raw) => {
  const text = raw.toString();
  const msg = JSON.parse(text);
  if (msg.t === "hello") {
    hello = msg;
    items.length = 0;
    items.push(...msg.items);
    lastSeq = msg.seq;
    console.log(`   hello seq=${msg.seq} items=${msg.items.length} running=${msg.status.running} model=${msg.detail.model?.modelId}`);
    console.log(`   availableThinkingLevels: ${JSON.stringify(msg.detail.availableThinkingLevels)}`);
    return;
  }
  if (msg.t === "pong" || msg.t === "unsubscribed") return;
  if (msg.t === "error") { console.error("   服务端错误:", msg.message); process.exit(1); }

  frames.push(msg.t);
  bytes += Buffer.byteLength(text);
  if (msg.seq !== lastSeq + 1) console.log(`   !! seq 跳变 ${lastSeq} → ${msg.seq}`);
  lastSeq = msg.seq;

  if (msg.t === "add") items.push(msg.item);
  else if (msg.t === "patch") {
    const target = items.find((i) => i.id === msg.id);
    if (!target) { console.error(`   失败: patch 指向未知 item ${msg.id}`); process.exit(1); }
    if (msg.append) applyText(target, msg.append.f, { s: (target[msg.append.f]?.s ?? "") + msg.append.s });
    for (const [k, v] of Object.entries(msg.set ?? {})) {
      if ((k === "text" || k === "thinking" || k === "output") && v) applyText(target, k, v);
      else target[k] = v;
    }
  } else if (msg.t === "status" && msg.status.running === false && frames.length > 2) {
    settled();
  }
});
ws.on("error", (e) => { console.error("WS 错误:", e.message); process.exit(1); });

await new Promise((r) => ws.once("open", r));

console.log(`4. 发送 prompt: "${prompt}"`);
const t0 = Date.now();
const result = await api(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: prompt }) });
console.log(`   HTTP 立即返回 (${Date.now() - t0}ms):`, JSON.stringify(result));

console.log("5. 等待 status.running=false …");
const timeout = setTimeout(() => { console.error("   超时 60s"); process.exit(1); }, 60_000);
await settledPromise;
clearTimeout(timeout);

const counts = frames.reduce((a, t) => ((a[t] = (a[t] ?? 0) + 1), a), {});
console.log(`   收到 ${frames.length} 帧 / ${bytes} 字节, 最终 seq=${lastSeq}`);
console.log("   帧统计:", JSON.stringify(counts));

// The whole point of v2: no SDK event kind reaches the client.
if (counts.event) { console.error("   失败: 仍在转发原始 SDK 事件"); process.exit(1); }

// A pending item that never settles means the client would spin forever.
const stillPending = items.filter((i) => i.pending);
if (stillPending.length > 0) {
  console.error(`   失败: ${stillPending.length} 个 item 仍是 pending`);
  process.exit(1);
}
console.log(`   ${items.length} 个 item 全部落定 ✓`);

console.log("6. 校验落盘（/items 与推流收敛到同一结果）…");
const page = await api(`/sessions/${id}/items`);
console.log("   items:", page.items.length, "kinds:", page.items.map((i) => i.kind).join(" → "));

const answer = page.items.filter((i) => i.kind === "assistant").at(-1);
console.log("   助手回复:", JSON.stringify(answer?.text.s));

// The live stream and the stored page must describe the same conversation.
const liveKinds = items.map((i) => i.kind).join(",");
const storedKinds = page.items.map((i) => i.kind).join(",");
if (liveKinds !== storedKinds) {
  console.error(`   失败: 推流得到 [${liveKinds}]，落盘是 [${storedKinds}]`);
  process.exit(1);
}
const liveAnswer = items.filter((i) => i.kind === "assistant").at(-1);
if (liveAnswer?.text.s !== answer?.text.s) {
  console.error(`   失败: 推流文本与落盘文本不一致`);
  console.error(`     推流: ${JSON.stringify(liveAnswer?.text.s)}`);
  console.error(`     落盘: ${JSON.stringify(answer?.text.s)}`);
  process.exit(1);
}
console.log("   推流与落盘一致 ✓");

console.log("7. 中止一个空闲会话（应 aborted=false 而非报错）…");
console.log("  ", JSON.stringify(await api(`/sessions/${id}/abort`, { method: "POST" })));

ws.close();
console.log("\n通过 ✓");
process.exit(0);
