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

console.log("2. 会话详情 …");
const detail = await api(`/sessions/${id}`);
console.log("   model:", detail.model, "thinking:", detail.thinkingLevel, "running:", detail.running);
console.log("   availableThinkingLevels:", detail.availableThinkingLevels);

console.log("3. WS 订阅 …");
const ws = new WebSocket(`ws://127.0.0.1:30150/ws?token=${encodeURIComponent(TOKEN)}`);
const seen = [];
let lastSeq = 0;
let settled;
const settledPromise = new Promise((r) => (settled = r));

ws.on("open", () => ws.send(JSON.stringify({ op: "subscribe", sessionId: id })));
ws.on("message", (raw) => {
  const msg = JSON.parse(raw.toString());
  if (msg.op === "subscribed") {
    console.log(`   已订阅 seq=${msg.seq} gap=${msg.gap} running=${msg.running}`);
    return;
  }
  if (msg.op !== "event") return;
  if (msg.seq !== lastSeq + 1) console.log(`   !! seq 跳变 ${lastSeq} → ${msg.seq}`);
  lastSeq = msg.seq;
  seen.push(msg.event.type);
  if (msg.event.type === "agent_settled") settled();
});
ws.on("error", (e) => { console.error("WS 错误:", e.message); process.exit(1); });

await new Promise((r) => ws.once("open", r));

console.log(`4. 发送 prompt: "${prompt}"`);
const t0 = Date.now();
const result = await api(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: prompt }) });
console.log(`   HTTP 立即返回 (${Date.now() - t0}ms):`, JSON.stringify(result));

console.log("5. 等待 agent_settled …");
const timeout = setTimeout(() => { console.error("   超时 60s"); process.exit(1); }, 60_000);
await settledPromise;
clearTimeout(timeout);

const counts = seen.reduce((a, t) => ((a[t] = (a[t] ?? 0) + 1), a), {});
console.log(`   收到 ${seen.length} 个事件, 最终 seq=${lastSeq}`);
console.log("   事件统计:", JSON.stringify(counts));

console.log("6. 校验落盘 …");
const page = await api(`/sessions/${id}/entries`);
const roles = page.entries.filter((e) => e.type === "message").map((e) => e.message.role);
console.log("   entries:", page.entries.length, "roles:", roles.join(" → "));

const assistant = page.entries.filter((e) => e.type === "message" && e.message.role === "assistant").at(-1);
const text = assistant?.message.content.filter((c) => c.type === "text").map((c) => c.text).join("");
console.log("   助手回复:", JSON.stringify(text));

console.log("7. 中止一个空闲会话（应 aborted=false 而非报错）…");
console.log("  ", JSON.stringify(await api(`/sessions/${id}/abort`, { method: "POST" })));

ws.close();
console.log("\n通过 ✓");
process.exit(0);
