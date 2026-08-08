/**
 * Harder end-to-end cases: reconnect replay, extension dialogs, external writes,
 * concurrent prompts.
 */
import { appendFileSync, mkdirSync, readFileSync } from "node:fs";
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
  return { status: res.status, body };
};

const must = async (path, init) => {
  const r = await api(path, init);
  if (r.status >= 400) throw new Error(`${path} → ${r.status} ${JSON.stringify(r.body)}`);
  return r.body;
};

function connect(onEvent) {
  const ws = new WebSocket(`ws://127.0.0.1:30150/ws?token=${encodeURIComponent(TOKEN)}`);
  ws.on("message", (raw) => onEvent(JSON.parse(raw.toString())));
  return new Promise((resolve) => ws.once("open", () => resolve(ws)));
}

const sub = (ws, sessionId, sinceSeq) =>
  ws.send(JSON.stringify({ op: "subscribe", sessionId, ...(sinceSeq !== undefined ? { sinceSeq } : {}) }));

let failures = 0;
const check = (label, ok, detail = "") => {
  console.log(`   ${ok ? "✓" : "✗"} ${label}${detail ? "  " + detail : ""}`);
  if (!ok) failures++;
};

// ---------------------------------------------------------------- 扩展对话框
console.log("A. 扩展弹对话框时 agent 不挂死 (/askme)");
{
  // 扩展命令自成一路：立即执行、不产生 agent_start/agent_settled。
  // 所以这里等的是「有事件流出」而非 settled——客户端的 loading 状态
  // 也必须遵守同一条规则，否则发 /命令 会永远转圈。
  const { id } = await must("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });
  const events = [];
  const ws = await connect((m) => { if (m.op === "event") events.push(m.event.type); });
  sub(ws, id);
  await new Promise((r) => setTimeout(r, 300));

  const t0 = Date.now();
  const res = await api(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "/askme 测试" }) });
  const elapsed = Date.now() - t0;
  check("prompt 被接受", res.status === 200, JSON.stringify(res.body));
  check("立即返回（对话框未阻塞）", elapsed < 5_000, `${elapsed}ms`);

  await new Promise((r) => setTimeout(r, 3_000));
  check("有事件流出", events.length > 0, events.join(","));

  const detail = await must(`/sessions/${id}`);
  check("会话未卡在运行中", detail.running === false, `running=${detail.running}`);
  ws.close();
}

// ---------------------------------------------------------------- 重连补齐
console.log("\nB. 断线重连按 sinceSeq 补齐");
{
  const { id } = await must("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });

  const first = [];
  const ws1 = await connect((m) => { if (m.op === "event") first.push(m); });
  sub(ws1, id);
  await new Promise((r) => setTimeout(r, 200));

  await must(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "数到三，只输出数字" }) });

  // 收到几个事件后立刻断开，模拟手机切后台/掉 Wi-Fi
  await new Promise((r) => {
    const t = setInterval(() => { if (first.length >= 3) { clearInterval(t); r(); } }, 20);
    setTimeout(() => { clearInterval(t); r(); }, 20_000);
  });
  const cutoffSeq = first.at(-1)?.seq ?? 0;
  ws1.terminate();
  console.log(`     断开于 seq=${cutoffSeq}`);

  await new Promise((r) => setTimeout(r, 2500));

  const second = [];
  let subscribed;
  const ws2 = await connect((m) => {
    if (m.op === "subscribed") subscribed = m;
    if (m.op === "event") second.push(m);
  });
  sub(ws2, id, cutoffSeq);

  await new Promise((r) => {
    const t = setInterval(() => {
      if (second.some((m) => m.event.type === "agent_settled")) { clearInterval(t); r(); }
    }, 50);
    setTimeout(() => { clearInterval(t); r(); }, 45_000);
  });

  const replayed = second.map((m) => m.seq);
  check("补齐无重复", new Set(replayed).size === replayed.length);
  check("补齐从断点之后开始", replayed.length === 0 || replayed[0] === cutoffSeq + 1, `首个 seq=${replayed[0]}`);
  check("seq 连续无缺口", replayed.every((s, i) => i === 0 || s === replayed[i - 1] + 1));
  check("gap 标志正确", subscribed?.gap === false, `gap=${subscribed?.gap}`);
  console.log(`     补齐 ${replayed.length} 个事件 (seq ${replayed[0]}..${replayed.at(-1)})`);
  ws2.close();
}

// ---------------------------------------------------------------- 并发 prompt
console.log("\nC. agent 忙时的第二条 prompt");
{
  const { id } = await must("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });
  const ws = await connect(() => {});
  sub(ws, id);
  await new Promise((r) => setTimeout(r, 200));

  await must(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "从一数到二十，每个数字一行" }) });
  await new Promise((r) => setTimeout(r, 800));

  const bare = await api(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "插一句" }) });
  check("不带 streamingBehavior 时 409", bare.status === 409 && bare.body.code === "session_busy", `${bare.status} ${bare.body.code}`);

  const queued = await api(`/sessions/${id}/prompt`, {
    method: "POST",
    body: JSON.stringify({ message: "然后说完毕", streamingBehavior: "followUp" }),
  });
  check("带 followUp 时被接受", queued.status === 200, JSON.stringify(queued.body));

  const aborted = await must(`/sessions/${id}/abort`, { method: "POST" });
  check("中止运行中的会话返回 true", aborted.aborted === true, JSON.stringify(aborted));
  ws.close();
}

// ---------------------------------------------------------------- 空闲中止
console.log("\nD. 中止空闲会话");
{
  const { id } = await must("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });
  const r = await must(`/sessions/${id}/abort`, { method: "POST" });
  check("返回 aborted=false 而非报错", r.aborted === false, JSON.stringify(r));
}

// ---------------------------------------------------------------- 外部写入
console.log("\nE. 外部进程改动会话文件后自动重载");
{
  const { id } = await must("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });
  await must(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "说 hi" }) });
  await new Promise((r) => setTimeout(r, 6000));

  const before = await must(`/sessions/${id}/entries`);
  const leaf = before.leafId;

  // 模拟另一个 pi 进程直接往文件里追加一条 entry
  const dir = join(homedir(), ".pi/agent/sessions", "--" + CWD.slice(1).replaceAll("/", "-") + "--");
  const { readdirSync } = await import("node:fs");
  const file = readdirSync(dir).find((f) => f.includes(id));
  const path = join(dir, file);

  appendFileSync(path, JSON.stringify({
    type: "message", id: "ext00001", parentId: leaf, timestamp: new Date().toISOString(),
    message: { role: "user", content: "来自外部 pi 进程", timestamp: Date.now() },
  }) + "\n");
  console.log(`     已外部追加一条 entry 到 ${file}`);

  const after = await must(`/sessions/${id}/entries`);
  const found = after.entries.some((e) => e.id === "ext00001");
  check("刷新后能读到外部写入的 entry", found);

  // 下一次 prompt 必须在重载后的树上追加，而不是过期的 leaf 上
  await must(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "说 bye" }) });
  await new Promise((r) => setTimeout(r, 6000));
  const final = await must(`/sessions/${id}/entries?limit=200`);
  const ids = new Set(final.entries.map((e) => e.id));
  check("外部 entry 仍在活跃分支上", ids.has("ext00001"), `共 ${final.entries.length} 条`);
}

console.log(failures === 0 ? "\n全部通过 ✓" : `\n${failures} 项失败 ✗`);
process.exit(failures === 0 ? 0 : 1);
