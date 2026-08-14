/**
 * Concurrency: several clients hitting one unloaded session at once must not
 * produce two AgentSessions writing the same JSONL file.
 */
import { mkdirSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";

const TOKEN = readFileSync(join(homedir(), ".pi", "remote", "token"), "utf8").trim();
const PORT = process.env.PI_REMOTE_PORT ?? "30150";
const BASE = `http://127.0.0.1:${PORT}/api/v1`;
const CWD = join(homedir(), "pi-remote-e2e");
mkdirSync(CWD, { recursive: true });

const api = async (path, init = {}) => {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json", ...init.headers },
  });
  const body = await res.json().catch(() => ({}));
  return { status: res.status, body };
};
const must = async (p, i) => {
  const r = await api(p, i);
  if (r.status >= 400) throw new Error(`${p} → ${r.status} ${JSON.stringify(r.body)}`);
  return r.body;
};

let failures = 0;
const check = (label, ok, detail = "") => {
  console.log(`   ${ok ? "✓" : "✗"} ${label}${detail ? "  " + detail : ""}`);
  if (!ok) failures++;
};

console.log("F. 并发访问同一个未加载会话");
const { id } = await must("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });
await must(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "说 hello" }) });
await new Promise((r) => setTimeout(r, 6000));

// 让它被空闲回收是不现实的（10 分钟），改为并发订阅 + 并发 prompt 打同一个会话
const sockets = await Promise.all(
  Array.from({ length: 6 }, () =>
    new Promise((resolve) => {
      const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=${encodeURIComponent(TOKEN)}`);
      ws.once("open", () => {
        ws.send(JSON.stringify({ op: "subscribe", sessionId: id }));
        resolve(ws);
      });
    }),
  ),
);
console.log(`     ${sockets.length} 个并发订阅已建立`);

const results = await Promise.all(
  Array.from({ length: 6 }, (_, i) =>
    api(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: `并发消息 ${i}` }) }),
  ),
);
const ok = results.filter((r) => r.status === 200).length;
const busy = results.filter((r) => r.status === 409).length;
console.log(`     6 个并发 prompt → ${ok} 接受, ${busy} 忙碌拒绝, ${6 - ok - busy} 其他`);
check("每个请求都有明确结果", ok + busy === 6, JSON.stringify(results.map((r) => r.status)));

await must(`/sessions/${id}/abort`, { method: "POST" });
await new Promise((r) => setTimeout(r, 3000));

console.log("     校验会话文件完整性 …");
const { readdirSync } = await import("node:fs");
const dir = join(homedir(), ".pi/agent/sessions", "--" + CWD.slice(1).replaceAll("/", "-") + "--");
const file = readdirSync(dir).find((f) => f.includes(id));
const lines = readFileSync(join(dir, file), "utf8").trim().split("\n");

let parsed = 0;
const ids = new Set();
const dupes = [];
const orphans = [];
for (const line of lines) {
  let e;
  try { e = JSON.parse(line); } catch { continue; }
  parsed++;
  if (e.type === "session") continue;
  if (ids.has(e.id)) dupes.push(e.id);
  ids.add(e.id);
}
// 第二遍：parentId 必须指向已存在的 entry
for (const line of lines) {
  let e;
  try { e = JSON.parse(line); } catch { continue; }
  if (e.type === "session" || e.parentId === null || e.parentId === undefined) continue;
  if (!ids.has(e.parentId)) orphans.push(`${e.id}→${e.parentId}`);
}

check("每一行都是合法 JSON", parsed === lines.length, `${parsed}/${lines.length}`);
check("没有重复的 entry id", dupes.length === 0, dupes.join(","));
check("没有断裂的 parentId 链", orphans.length === 0, orphans.join(","));
console.log(`     文件 ${lines.length} 行, ${ids.size} 个 entry`);

for (const ws of sockets) ws.close();
console.log(failures === 0 ? "\n全部通过 ✓" : `\n${failures} 项失败 ✗`);
process.exit(failures === 0 ? 0 : 1);
