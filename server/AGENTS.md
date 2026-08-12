# pi-remote-bridge — 开发笔记

```bash
npm run dev        # tsx watch, 端口 30150
npm run typecheck
npm test           # 单元测试 (node --test)
npm run test:e2e   # 端到端，需要先跑起服务
```

Token 在 `~/.pi/remote/token`，首次启动自动生成并打印。启动横幅附带二维码（`qr.ts`），手机端扫码即得 URL+token，不用手抄 32 位 token。

## 架构

```
索引（找会话）        sessions/scan.ts    readdir，不解析任何文件
浏览（只读）          sessions/model.ts   解析后的树，按 (mtime,size) 缓存
                     store.ts            投影到 wire 类型
执行（写入）          agent-pool.ts → createAgentSession()      仅发消息时创建
事件流                session.subscribe() → ws.ts 扇出给所有订阅者
```

**浏览走文件、执行才起 agent。** 会话列表和历史只读 JSONL，切换会话不产生任何 agent 开销——
这是「快速切换不卡」的前提。

**读路径分两层，因为两件事的成本差 200 倍。** `id → path` 是索引级查询：文件名里就带着
session id（`<时间戳>_<id>.jsonl`，本机 135 个文件全部吻合），一次 `readdir` 2.6ms 就能建好，
不用打开任何文件。而 `SessionManager.open()` 会**立刻全量解析**——最大的会话 7.7MB / 1460 条
entry，一次 52ms。早先两者混在一起：`findSession()` 为了拿一个路径去跑
`SessionManager.listAll()`，那是 **476ms**（135 个会话全部解析一遍），而它挂在每个
`/entries`、每个 detail、冷 agent 的第一条 prompt 前面。

**两层缓存都以文件的 `(mtime, size)` 为键，没有 TTL。** 会话文件是 append-only 的，所以这个键
命中就等于「解析结果可证明是最新的」，任何写入者（我们自己或另一个 pi TUI）都会让它失效。这比
它取代的 2 秒 TTL 既便宜又**更准**：外部进程追加的内容立刻可见，不用等窗口过期。

实测（135 个会话 / 最大 7.7MB）：

| | 之前 | 现在 |
|---|---|---|
| `id → path` | 476ms | 0.002ms（`locate()` 135 次共 0.3ms） |
| 会话列表（无变动） | 476ms | 2.1ms |
| 开一个会话（detail + 首页） | 3 次全量解析 | 1 次（detail 冷 123ms → 热 0.1ms，`/entries` 1.0ms） |
| 往前翻 20 页 | 20 次全量解析 ≈ 1040ms | 4.2ms |

新写的 summary 读取器逐字段对齐了 SDK 内部的 `buildSessionInfo`（它没有导出），135 个会话
**零差异**——改动这块时用同样的对比方式验证，不要凭眼看。唯一故意的差别是不构造
`allMessagesText`：那是给本地搜索用的全文拼接，wire 上早就丢掉了，构造它白白翻倍内存和读取成本。

## 实测数据（决定了瘦身规则）

本机 135 个会话，最大 7.7MB / 1460 条 entry（这一节的分布是在 79 个会话 / 最大 2.7MB 时测的，
量级结论没变）。单条 entry 的体量分布：

| 大小 | 来源 |
|---|---|
| 361KB | `toolResult` 的 `image` 块（read 读了 PNG） |
| 45KB | `toolResult` 文本（read 大文件） |
| 31KB | `toolResult` 文本（bash 输出） |
| 25KB | assistant `toolCall.arguments`（write 的文件内容在参数里） |
| 12KB | assistant `thinking` 块 |

`slim.ts` 处理全部五种。最大会话经分页后：20 页，最大单页 156KB。全会话共 343 处被瘦身
（thinking 230、arguments 108、text 4、image 1）。

**注意 `toolCall.arguments`**：按字段逐个截断，不整体丢弃——`write` 把整个文件塞进 `content`，
但 UI 需要旁边的 `file_path` 才能渲染出「▸ write src/foo.ts」。

## 陷阱

### prompt 不能 await

`session.prompt()` 要等整个 run 跑完才 resolve（可能几分钟）。HTTP 响应挂在 `preflightResult`
回调上，它在「被接受」的瞬间触发。实测 HTTP 6ms 返回。接受之后的错误走事件流，不走这个响应。

### 并发 prompt 必须串行化 —— 否则静默丢消息

`withPromptLock` 保护「busy 检查 + prompt 调用」这段临界区。没有它时，并发请求在同一个 tick
里全部读到 `isStreaming === false`，全部通过检查，全部调 `prompt()`。**实测 6 个并发请求全部
返回 accepted，但只有第一条进了对话，另外 5 条被静默丢弃。** 两台设备同时发、或用户手抖双击，
就会丢消息。加锁后是 1 接受 + 5 个明确的 409 `session_busy`。

临界区只覆盖「准入」，不覆盖 run 本身，所以排队的 followUp 仍然立即返回。

### 扩展命令不产生 agent_settled

普通 prompt 走 `agent_start … agent_settled`。**扩展命令（`/askme` 之类）不走**——它就地执行，
只发出 handler 产生的消息。用 `piremote-demo.ts` 实测：只有一条 `custom` 消息，没有任何生命
周期事件。

所以客户端的 loading 状态**不能只依赖 `agent_settled`**，否则发个斜杠命令就永远转圈。

### 不要绑定 uiContext

`createAgentSession` 时故意不传 `uiContext`。SDK 于是使用自带的 `noOpUIContext`，这让
`ctx.hasUI === false`，行为良好的扩展会主动走非交互路径，而不是弹一个没人能回答的框。
即使真弹了，它的 dialog 方法也立即 resolve（`confirm→false`，`select/input/editor→undefined`），
不会挂住 run。

一旦传入自定义 uiContext，`hasUI` 就变成 true，扩展反而开始弹框——想转发 notify 时要权衡这点。

### 外部 pi 进程会静默损坏会话 —— 但检测本身有三个大坑

同时用 pi TUI 打开同一个文件，两个进程各自维护内存中的树和 leaf，互相看不见对方的追加，会产生
`parentId` 错乱的分支。`LiveAgent` 记录文件 mtime/size 来检测。三个踩过的坑：

**1. 指纹必须延后一拍取。** SDK 在写入真正落盘**之前**就发出 `entry_appended`，同步 `readStat`
拿到的是写入前的大小，于是下一次 acquire 就把自己的写入当成别人的。`createAgentSession()` 同理
——它启动时会追加 `model_change`/`thinking_level_change`，那写入未必在构造返回时已落盘。两处都用
`setImmediate` 重新 stat。症状是日志里每次 prompt 都刷 `changed externally — reloading`。

**2. 只有写入路径才检查。** `acquire(sessionId, forWrite)`：WebSocket 订阅传 false。否则每次订阅
都可能触发重载，而重载会……

**3. 重载必须搬走订阅者。** `destroy()` 会 `listeners.clear()`。早期版本重载时直接 destroy + create，
结果是**客户端 WebSocket 还连着，但再也收不到任何事件**——UI 一直转圈却毫无报错，极难排查。现在
`reload()` 把 listener 集合搬到新 agent 上，并推一条 `session_reloaded` 让客户端重新拉取（新 agent
的 seq 从 0 开始，客户端的游标已失效）。

### 别在 append 时去清会话缓存

`publish()` 里**故意没有**任何缓存失效调用。早先每条 `entry_appended`/`message_end` 都
`invalidateSessionCache()`，于是跑 run 期间快照基本永远是冷的，下一个请求就要吃一次 476ms 全量
重扫——而快照本来就有 2 秒 TTL，那次调用真正的作用只是「把 2 秒内的缓存换成立刻重扫」。

想改成「就地 patch 那一条缓存」也不行：要让它的 `(mtime, size)` 继续有效就得 `stat`，而 SDK 在
写入真正落盘**之前**就发出 `entry_appended`（同一个 race 逼出了上面那两处 `setImmediate`）。
写入前取到的 stamp 会看起来永远有效，于是永久返回脏 summary。

所以 stamp 是唯一的判据：append 改变它，下一次 `summaries()` 只重读这一个文件，其余 134 个跳过。

### 读路径拿 live agent 的树，必须先确认没被外部写过

`liveTree()` 在 `wasWrittenByOthers()` 为真时返回 `undefined`，让读者退回文件。

理由不是理论上的：内存树只有在没人绕过我们写文件时才是权威。外部 pi TUI 追加的 entry 只在文件
里，agent 要等下一次**写路径** `acquire(forWrite=true)` 才重载——中间这段时间把内存树交给读者，
`GET /entries` 就看不到外部那条消息，而文件本身明明有。`e2e2.mjs` 的 E 项就是抓这个的，第一版
实现直接被它挡下来了。

代价是每次读多一次 `statSync`（约 0.04ms）。

### 新建会话在首次写入前不存在于磁盘

`SessionManager.create()` 延迟到第一条 entry 才落盘，期间任何目录扫描都看不到它。
`sessions/scan.ts` 的 `pending` 表登记这类会话，让 `locate()` 和 `summaries()` 都能看到，避免
客户端拿到 id 却立刻 404。真实文件被扫到后自动清除，中间不会出现重复的一行。

### 展示要用 buildContextEntries 而非 getEntries

会话是树。`getEntries()` 会把废弃分支也交出来。`buildContextEntries()` 给的是当前活跃分支并已
应用 compaction，跟 TUI 看到的一致。

### 两个 ThinkingLevel

pi-ai 的 `ThinkingLevel` **不含** `"off"`（那个叫 `ModelThinkingLevel`）；pi-agent-core 的含。
`AgentSession.setThinkingLevel()` 要的是后者。`protocol.ts` 里镜像了后者。

### HttpError 不用构造函数参数属性

`node --experimental-strip-types` 只擦类型不转换代码，参数属性（`constructor(readonly x: T)`）
会让 `node --test` 直接语法报错。字段显式赋值。

## API

见 `protocol.ts`（wire 类型）和 `index.ts`（路由注册）。所有请求要求
`Authorization: Bearer <token>`；WS 用 `?token=` 查询参数。

`cwd` 作为查询参数一律 base64url 编码。
