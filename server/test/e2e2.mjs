/**
 * Harder end-to-end cases: reconnect replay, extension dialogs, external writes,
 * concurrent prompts.
 */
import { appendFileSync, mkdirSync, readFileSync } from "node:fs";
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
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?token=${encodeURIComponent(TOKEN)}`);
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
  let status;
  let sawRunning = false;
  const pending = new Set();
  const ws = await connect((m) => {
    if (m.t === "hello" || m.t === "status") {
      status = m.status;
      if (m.status.running) sawRunning = true;
      return;
    }
    if (m.t === "add" && m.item.pending) pending.add(m.item.id);
    if (m.t === "patch" && m.set?.pending === false) pending.delete(m.id);
  });
  sub(ws, id);
  await new Promise((r) => setTimeout(r, 300));

  const t0 = Date.now();
  const res = await api(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "/askme 测试" }) });
  const elapsed = Date.now() - t0;
  check("prompt 被接受", res.status === 200, JSON.stringify(res.body));
  check("立即返回（对话框未阻塞）", elapsed < 5_000, `${elapsed}ms`);

  await new Promise((r) => setTimeout(r, 3_000));
  // 这里真正要守住的是「客户端不会永远转圈」。旧设计靠客户端启发式：扩展命令不产生
  // agent_start/agent_settled，所以不能只等 settled。v2 里 running 由服务端从
  // session.isStreaming 推出——扩展命令从不把它置真，于是根本没有要清的转圈状态。
  check("从未被标记为运行中", sawRunning === false, `sawRunning=${sawRunning}`);
  check("没有留下未落定的 item", pending.size === 0, `pending=${[...pending].join(",")}`);

  // v2 的关键：忙碌与否由 status 权威给出，客户端不再从生命周期事件推断。
  // 扩展命令根本不把 isStreaming 置真，所以这里天然是 false——旧设计里
  // 客户端等 agent_settled 会永远转圈。
  check("status 明确不在运行", status?.running === false, `running=${status?.running}`);
  ws.close();
}

// ---------------------------------------------------------------- 重连补齐
console.log("\nB. 运行中断线重连：靠重发整条 item 补齐");
{
  const { id } = await must("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });

  const before = [];
  const ws1 = await connect((m) => { if (m.t === "add" || m.t === "patch" || m.t === "status") before.push(m); });
  sub(ws1, id);
  await new Promise((r) => setTimeout(r, 200));

  await must(`/sessions/${id}/prompt`, {
    method: "POST",
    body: JSON.stringify({ message: "写一篇约600字的中文短文，讲解 TCP 拥塞控制从 Reno 到 BBR 的演进，要有小标题。" }),
  });

  // 等到确实有一条 pending 的消息在流，再断开——这样断线期间服务端一定还在往前走。
  // 不按推送条数等：帧数取决于回合时长（≈时长/80ms），跟着模型快慢浮动。
  await new Promise((r) => {
    const t = setInterval(() => {
      const streaming = before.some((m) => m.t === "add" && m.item.pending) && before.length >= 12;
      if (streaming) { clearInterval(t); r(); }
    }, 20);
    setTimeout(() => { clearInterval(t); r(); }, 120_000);
  });
  const cutoffSeq = before.at(-1)?.seq ?? 0;
  ws1.terminate();
  console.log(`     断开于 seq=${cutoffSeq}（已收 ${before.length} 个推送）`);

  // 离开一会儿，让服务端攒下我们没看到的变化。
  await new Promise((r) => setTimeout(r, 2500));

  const after = [];
  let helloed = false;
  const ws2 = await connect((m) => {
    if (m.t === "hello") { helloed = true; return; }
    if (m.t === "add" || m.t === "patch" || m.t === "status") after.push(m);
  });
  sub(ws2, id, cutoffSeq);
  await new Promise((r) => {
    const t = setInterval(() => {
      if (after.some((m) => m.t === "status" && m.status.running === false)) { clearInterval(t); r(); }
    }, 50);
    setTimeout(() => { clearInterval(t); r(); }, 180_000);
  });
  ws2.close();

  check("没有退化成全量快照", helloed === false, `hello=${helloed}`);

  // 补齐是「重发整条 item」，不是重放错过的那一串 delta——这正是环形缓冲能删掉的原因。
  const resent = after.filter((m) => m.t === "add" && before.some((b) => b.t === "add" && b.item.id === m.item.id));
  check("补齐靠重发已有的 item", resent.length > 0, `重发 ${resent.length} 条`);

  // 收敛的唯一标准：把断线前 + 补齐后的推送按客户端语义折叠，要等于落盘。
  const items = await must(`/sessions/${id}/items?limit=200`);
  const stored = items.items.filter((i) => i.kind === "assistant").at(-1)?.text?.s ?? "";
  const list = [];
  const applyText = (t, k, patch) => {
    const cur = t[k];
    t[k] = { s: patch.s ?? cur?.s ?? "", ...(patch.more ?? cur?.more ? { more: patch.more ?? cur?.more } : {}) };
  };
  for (const m of [...before, ...after]) {
    if (m.t === "add") {
      const at = list.findIndex((i) => i.id === m.item.id);
      if (at === -1) list.push(m.item); else list[at] = m.item;   // upsert
    } else if (m.t === "patch") {
      const target = list.find((i) => i.id === m.id);
      if (!target) continue;
      if (m.append) applyText(target, m.append.f, { s: (target[m.append.f]?.s ?? "") + m.append.s });
      for (const [k, v] of Object.entries(m.set ?? {})) {
        if ((k === "text" || k === "thinking" || k === "output") && v) applyText(target, k, v);
        else target[k] = v;
      }
    }
  }
  const folded = list.filter((i) => i.kind === "assistant").at(-1)?.text?.s ?? "";
  check(
    "折叠后的文本与落盘一致",
    folded === stored && stored.length > 0,
    folded === stored ? `${stored.length} 字` : `折叠 ${folded.length} 字 vs 落盘 ${stored.length} 字`,
  );
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

  // The newest item's id is its entry id (only tool rows derive theirs from a
  // call site), so it is a valid parent for a hand-written append.
  const beforeItems = await must(`/sessions/${id}/items`);
  const leaf = beforeItems.items.at(-1)?.id ?? null;

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

  const after = await must(`/sessions/${id}/items`);
  const found = after.items.some((i) => i.id === "ext00001");
  check("刷新后能读到外部写入的 entry", found, `共 ${after.items.length} 条`);

  // 下一次 prompt 必须在重载后的树上追加，而不是过期的 leaf 上
  await must(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "说 bye" }) });
  await new Promise((r) => setTimeout(r, 6000));
  const final = await must(`/sessions/${id}/items?limit=200`);
  const ids = new Set(final.items.map((i) => i.id));
  check("外部 entry 仍在活跃分支上", ids.has("ext00001"), `共 ${final.items.length} 条`);
}

// ------------------------------------------------ 回合结束后才重连（幽灵 id）
console.log("\nF. 断线在回合中、重连在回合后");
{
  const { id } = await must("/sessions", { method: "POST", body: JSON.stringify({ cwd: CWD }) });

  const before = [];
  const ws1 = await connect((m) => { if (m.t === "add" || m.t === "patch" || m.t === "status") before.push(m); });
  sub(ws1, id);
  await new Promise((r) => setTimeout(r, 200));
  await must(`/sessions/${id}/prompt`, { method: "POST", body: JSON.stringify({ message: "数到十，每个数字一行" }) });

  // 断在流式当中：此时那条消息还挂在铸造出来的 live-N 上。
  await new Promise((r) => {
    const t = setInterval(() => {
      if (before.some((m) => m.t === "add" && m.item.pending) && before.length >= 4) { clearInterval(t); r(); }
    }, 20);
    setTimeout(() => { clearInterval(t); r(); }, 90_000);
  });
  const cutoffSeq = before.at(-1)?.seq ?? 0;
  const liveIds = before.filter((m) => m.t === "add" && m.item.id.startsWith("live-")).map((m) => m.item.id);
  ws1.terminate();
  console.log(`     断开于 seq=${cutoffSeq}，手里的铸造 id: ${liveIds.join(",") || "无"}`);

  // 等回合彻底结束——这样 live-N 已经落盘成真实 entry id，服务端列表里再也没有它。
  await new Promise((r) => setTimeout(r, 15_000));

  const after = [];
  let helloed = false;
  const ws2 = await connect((m) => {
    if (m.t === "hello") { helloed = true; return; }
    if (m.t === "add" || m.t === "patch" || m.t === "status") after.push(m);
  });
  sub(ws2, id, cutoffSeq);
  await new Promise((r) => setTimeout(r, 2500));
  ws2.close();

  check("落定后重连仍走增量", helloed === false, `hello=${helloed}`);

  // 关键：重发回来的必须还用客户端认识的那个 live-N，否则同一条消息会存两份。
  const resentIds = after.filter((m) => m.t === "add").map((m) => m.item.id);
  const kept = liveIds.every((lid) => resentIds.includes(lid));
  check("按客户端认识的 id 重发", liveIds.length === 0 || kept, `重发 ${resentIds.join(",") || "无"}`);

  // 而且那条消息必须已落定——不然界面会永远停在 pending。
  const settled = after.filter((m) => m.t === "add" && liveIds.includes(m.item.id));
  check(
    "重发回来的消息已落定",
    settled.length > 0 && settled.every((m) => !m.item.pending),
    settled.map((m) => `${m.item.id}:pending=${m.item.pending ?? false}`).join(" ") || "没有重发",
  );
}

console.log(failures === 0 ? "\n全部通过 ✓" : `\n${failures} 项失败 ✗`);
process.exit(failures === 0 ? 0 : 1);
