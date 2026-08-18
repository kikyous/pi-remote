# Pi Remote

用手机 / 平板远程控制局域网内 PC 上的 pi coding agent。

Android 原生 app（Kotlin + Jetpack Compose）+ PC 端 Node 桥接服务。

## 为什么需要服务端

pi 没有任何网络服务能力，只有两个可编程入口：`pi --mode rpc`（stdin/stdout JSONL）和 Node SDK
（`@earendil-works/pi-coding-agent`）。手机无法直连 pi，PC 端必须跑一个桥接服务。

本项目采用 **SDK 内嵌**：单个 Node 进程用 `createAgentSession()` 管理多个活跃会话。

## 架构

```
Android (Kotlin/Compose)          PC: Node 服务 (单进程)
  │                                 │
  ├─ GET  /projects ───────────────▶ SessionManager.listAll() 分组      ┐ 只读
  ├─ GET  /sessions?cwd= ──────────▶ SessionManager.list(cwd)          │ 不起
  ├─ GET  /sessions/{id}/entries ──▶ SessionManager.open(path)         ┘ agent
  │                                 │
  ├─ POST /sessions/{id}/prompt ───▶ AgentPool.acquire(id)
  │                                 │   └─ createAgentSession({sessionManager})
  ├─ WS   /ws  subscribe{id,since} ◀── session.subscribe() 事件扇出
  └─ 通知 / 前台服务                  └─ 空闲 10 分钟 dispose()
```

**核心分工：浏览走文件、执行才起 agent。** 会话列表和历史都只读 JSONL，切换会话不产生任何
进程 / agent 开销——这是「快速切换不卡」的前提。只有发消息时才为该会话创建 `AgentSession`，
空闲后回收。

## 需求与对应设计

| 需求 | 做法 |
|---|---|
| 按工作目录加载会话列表、切换会话 | `SessionManager.listAll()` 按 cwd 分组；切换只读文件，不起 agent |
| 快速切换数据不能乱 | 每会话独立 `SessionStore` + epoch 作废在途响应；WS 按 sessionId 路由 |
| 长会话 lazy load 不崩 | 尾部优先分页 + 单条 entry 截断 + 客户端 400 条内存上限 |
| 在当前工作目录新建会话 | `POST /sessions {cwd}` |
| 界面简洁好看 | Material 3 深色优先；手机单栏 / 平板双栏；assistant 无气泡铺文本 |
| 每会话独立切模型、思考等级 | `PATCH /sessions/{id} {model, thinkingLevel}` |

## 实测数据规模（决定了必须做什么）

本机 78 个会话文件：

- 最大会话 **2.7MB / 990 条 entry**
- **单条 entry 最大 361KB**，8 条超过 20KB

→ 分页与单条截断**两件事都必须做**，只做分页仍会被一条 toolResult 打爆。

服务端瘦身规则：

- `toolResult` content / `bashExecution.output` 超 8KB → 截断 + `{truncated, fullSize}`，
  点「展开全部」走 `/full` 端点
- assistant 的 `thinking` 块只回前 200 字 + `{deferred: true}`（默认折叠，不占首屏带宽）
- 列表接口剥掉 `SessionInfo.allMessagesText`（SDK 会把整个会话文本塞进这个字段）

## 多客户端

多设备连同一个 server 是预期用法，但**服务端不做任何仲裁**——没有锁、没有设备身份、没有
来源标注、没有 CAS。一致性靠客户端手动刷新兜底。

天然安全、无需处理的：不同会话（按 sessionId 分片）、同会话只读浏览（WS 扇出给所有订阅者，
实时同步是免费的）、同时新建会话、同会话同时发消息（同一 sessionId 只有一个 `AgentSession`
实例，不存在两个写者）。

服务端语义：agent 空闲 → 直接执行；agent 忙 → 按 `streamingBehavior` 入队（`steer` 插队 /
`followUp` 排队）；未带该字段 → 409。**这套队列语义单设备本来就需要**，不是为多设备加的。

**唯一需要防的是外部 pi 进程**：同时用 pi TUI 打开同一会话文件会产生 `parentId` 错乱的分支，
是唯一会静默损坏数据的场景。防法零成本：`LiveAgent` 记录文件 mtime/size，每次 acquire 和
刷新读取前 `stat` 比对，磁盘更新就 dispose 并重新 open。不做 `fs.watch`、不做告警广播。

刷新 = **以服务器为准重建本地状态**，不是增量 append（别的设备的消息按时间穿插，append 会
插错位置）。触发点：列表页下拉、对话页顶栏刷新图标、App 回前台自动。

## 目录结构

```
pi-remote/
  server/                  Node 20+ / TypeScript
    src/index.ts           HTTP + WS 启动、token 鉴权、绑定 0.0.0.0
    src/store.ts           只读层：projects/sessions/entries + mtime 缓存
    src/agent-pool.ts      Map<sessionId, LiveAgent>、空闲回收、headless UI ctx
    src/slim.ts            entry 瘦身：截断 + thinking 延迟
    src/protocol.ts        wire 类型
  app/                     Gradle / Kotlin / Compose
    app/src/main/java/com/piremote/
      net/                 OkHttp + WS 客户端 + 事件解码
      data/                SessionRepository、每会话 SessionStore
      ui/                  ProjectList / SessionList / Chat / Settings
      service/             AgentForegroundService + 通知
```

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/projects` | 按 cwd 分组：`{cwd, name, sessionCount, lastModified}` |
| GET | `/api/v1/sessions?cwd=` | 该目录的会话列表（cwd 用 base64url 编码） |
| GET | `/api/v1/sessions/{id}` | `{cwd, name, model, thinkingLevel, leafId, totalEntries, running}` |
| GET | `/api/v1/sessions/{id}/entries?before=&limit=50` | 尾部优先分页 → `{entries, hasMore, oldestId}` |
| GET | `/api/v1/sessions/{id}/entries/{entryId}/full?part=` | 取被截断 / 延迟的完整内容 |
| GET | `/api/v1/models` | 可用模型 + 各自支持的 thinking levels + 默认模型 |
| POST | `/api/v1/sessions` | 新建 `{cwd, model?, thinkingLevel?}` |
| POST | `/api/v1/sessions/{id}/prompt` | `{text, streamingBehavior?}` |
| POST | `/api/v1/sessions/{id}/abort` | 中止 |
| PATCH | `/api/v1/sessions/{id}` | `{model?, thinkingLevel?, name?}` |

WS `/ws?token=`：客户端 `{op:"subscribe", sessionId, sinceEntryId?}`，服务端推
`{sessionId, seq, event}`。`seq` 单调递增供客户端发现丢包；30s 心跳。

**headless 扩展 UI 降级（必须实现）**：MVP 不做对话框转发，但扩展调 `ctx.ui.confirm()` 时
若无人应答，agent 会永久挂起。通过 `session.bindExtensions()` 注入降级实现：`confirm()→false`、
`select()/input()/editor()→undefined`、`notify()→` 转 WS 事件，其余 no-op。

## Android 三个技术难点

**快速切换不乱** —— 不用「全局 chat state 被切换时覆写」，那是串台根因。每会话一个
`SessionStore` 持有自己的 `StateFlow`（LRU 保 3~5 个），切换只是换订阅；每个请求携带 epoch，
对不上就丢弃；Compose 侧 `key(sessionId) { ChatScreen() }` 强制重建。

**长会话不崩** —— 首屏尾部 50 条，`LazyColumn(reverseLayout = true)`，数据传
`items.asReversed()`（index 0 = 最新 = 底部锚点）；向上滚（高 index 端）用 `oldestId`
作游标续拉；内存上限 400 条，超出丢弃最旧并记游标。
底部锚定后不再需要 stick/滚动补偿：新消息、流式、键盘、首屏全部免费；
展开卡片靠 400dp 限高 + 卡片内部滚动（方案 2）控制位移。

**流式不掉帧** —— `text_delta` 每秒上百条，逐条 recompose 会卡死。流式文本单独放一个
`MutableState<String>`，只有最后一条气泡订阅；delta 经 `Channel(CONFLATED)` + ~33ms 定时
flush 合批；**Markdown 只对已完成消息渲染**，流式中用纯文本，`message_end` 到达再替换。

## 路线

| 阶段 | 内容 |
|---|---|
| M0 | plan.md + server 骨架（HTTP + token + `/projects`） |
| M1 | 只读层：`/sessions`、`/entries` 分页、slim 截断 |
| M2 | AgentPool（含 mtime 重载）+ `/prompt` + WS + headless UI 降级 |
| M3 | Android：连接设置、列表、只读浏览 + lazy load + 刷新 |
| M4 | Android：发消息、流式渲染、工具卡片、中止、插队/排队 |
| M5 | 新建会话、每会话模型 / thinking 切换 |
| M6 | 前台服务、完成通知、断线重连补齐 |
| M7 | 打磨：平板双栏、深色主题、空 / 错误态 |

## 明确不做（MVP 之外）

图片附件、扩展 UI 对话框转发、文件浏览 / git diff、fork 与 tree 分支导航、手动 compact、
mDNS 发现、公网访问。

多客户端相关：会话软锁 / 控制权争夺、设备身份识别与来源标注、abort 归属提示、模型切换 CAS、
`fs.watch` 外部变更告警。

---

Pi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, skills, or TUI):
- Main documentation: /Users/chen/.npm-global/lib/node_modules/@agegr/pi-web/node_modules/@earendil-works/pi-coding-agent/README.md
- Additional docs: /Users/chen/.npm-global/lib/node_modules/@agegr/pi-web/node_modules/@earendil-works/pi-coding-agent/docs
- Examples: /Users/chen/.npm-global/lib/node_modules/@agegr/pi-web/node_modules/@earendil-works/pi-coding-agent/examples (extensions, custom tools, SDK)
- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... under Examples, not the current working directory
- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers (docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md), environment variables (docs/environment-variables.md)
- When working on pi topics, read the docs and examples, and follow .md cross-references before implementing
- Always read pi .md files completely and follow links to related docs (e.g., tui.md for TUI API details)
