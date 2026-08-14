# pi-remote-bridge — 开发笔记

```bash
npm run dev            # tsx watch, 端口 30150
npm run typecheck
npm test               # 单元测试 (node --test)
npm run test:e2e       # pi 后端端到端，需要先跑起服务
```

Token 在 `~/.pi/remote/token`（dsh 后端是 `~/.pi/remote/dsh/token`），首次启动自动生成并打印。启动横幅附带二维码（`qr.ts`），手机端扫码即得 URL+token，不用手抄 32 位 token。

## 两个后端

`protocol.ts` 是 app 认的东西，它里面没有任何 agent 的痕迹——**兼容负担全部压在 `backend.ts`
这条缝上**。缝以上（`http.ts` / `ws.ts` / `git.ts` / `live/hub.ts` / `live/coalesce.ts` /
`shorten.ts` / `refs.ts`）不 import 任何后端模块；缝以下每个 agent 一个目录。

| | pi | dsh |
|---|---|---|
| 怎么驱动 | 进程内 SDK（`createAgentSession`） | HTTP + 两条 WS 连本机 `dsh web` |
| 历史从哪来 | `~/.pi/agent/sessions/**.jsonl`，按 `(mtime,size)` 缓存解析 | `session.history` RPC，缓存在内存，靠 mux 保持最新 |
| item id | entry 的 ULID | 事件的 `seq`（单调且连续，兼作分页游标和 ref 的地址） |
| 流式与落盘 | 两条流，要靠引用相等 + 延后 `stat` 对账 | 同一条带 `seq` 的流，按顺序到达 |
| 起 agent | 发消息时 `createAgentSession()` | 发消息时 harness 自己 resume |

选后端：`--backend pi|dsh`（默认 pi），dsh 用 `--dsh-url` 指定 host（默认 `http://127.0.0.1:3080`）。
两个后端的模块都是**按需 import** 的，跑 dsh 不加载 pi SDK。

## 架构（pi 后端）

```
索引（找会话）        backends/pi/scan.ts      readdir，不解析任何文件
浏览（只读）          backends/pi/model.ts     解析后的树，按 (mtime,size) 缓存
                     backends/pi/items.ts     entry → Item[]（唯一定义 item 最终形态的地方）
                     backends/pi/store.ts     投影到 wire 类型
执行（写入）          backends/pi/agent-pool.ts → createAgentSession()   仅发消息时创建
事件流                backends/pi/translate.ts SDK 事件 → item 变更（唯一认识 AgentSessionEvent 的文件）
                     live/coalesce.ts         80ms / 4KB 攒批
                     live/hub.ts              seq 分配、订阅扇出、补齐记账（两个后端共用）
                     ws.ts                    扇出给所有订阅者
```

**浏览走文件、执行才起 agent。** 会话列表和历史只读 JSONL，切换会话不产生任何 agent 开销——
这是「快速切换不卡」的前提。

**读路径分两层，因为两件事的成本差 200 倍。** `id → path` 是索引级查询：文件名里就带着
session id（`<时间戳>_<id>.jsonl`，本机 135 个文件全部吻合），一次 `readdir` 2.6ms 就能建好，
不用打开任何文件。而 `SessionManager.open()` 会**立刻全量解析**——最大的会话 7.7MB / 1460 条
entry，一次 52ms。早先两者混在一起：`findSession()` 为了拿一个路径去跑
`SessionManager.listAll()`，那是 **476ms**（135 个会话全部解析一遍），而它挂在每个翻页请求、每个
detail、冷 agent 的第一条 prompt 前面。

**两层缓存都以文件的 `(mtime, size)` 为键，没有 TTL。** 会话文件是 append-only 的，所以这个键
命中就等于「解析结果可证明是最新的」，任何写入者（我们自己或另一个 pi TUI）都会让它失效。这比
它取代的 2 秒 TTL 既便宜又**更准**：外部进程追加的内容立刻可见，不用等窗口过期。

实测（135 个会话 / 最大 7.7MB）：

| | 之前 | 现在 |
|---|---|---|
| `id → path` | 476ms | 0.002ms（`locate()` 135 次共 0.3ms） |
| 会话列表（无变动） | 476ms | 2.1ms |
| 开一个会话（detail + 首页） | 3 次全量解析 | 1 次（冷 123ms → 热 0.1ms；首页与 detail 共用） |
| 往前翻 20 页 | 20 次全量解析 ≈ 1040ms | 4.2ms（item 列表按文件版本记忆化） |

新写的 summary 读取器逐字段对齐了 SDK 内部的 `buildSessionInfo`（它没有导出），135 个会话
**零差异**——改动这块时用同样的对比方式验证，不要凭眼看。唯一故意的差别是不构造
`allMessagesText`：那是给本地搜索用的全文拼接，wire 上早就丢掉了，构造它白白翻倍内存和读取成本。

## 线上传的是 item，不是 SDK 事件

**`live/translate.ts` 是全仓库唯一认识 `AgentSessionEvent` 的文件。** 这条边界是整个协议 v2 的
支点。之前 WS 上原封不动转发 SDK 事件，代价是：

- **51% 的字节 / 31% 的帧客户端从不读**（`toolcall_*`、`text_end`、`thinking_end`、
  `turn_start/end`、`agent_end` 在 Kotlin 里引用数为 0；`message_start/end` 的整个 `message`
  载荷也没人读）。
- **同一段 assistant 文本过线三次**：deltas → `message_end.message` → 合成的 `entry_appended`。
- 客户端必须懂 `assistantMessageEvent.type`、`partialResult.content[].text`、
  `message.usage.cost.total`，SDK 加一种事件就可能弄坏它。

现在线上只有 `hello`/`add`/`patch`/`status` 四种推送，实测同一个回合 **21730 字节 / 139 帧 →
1502 字节 / 12 帧**（`src/live/translate.test.ts` 用抓下来的真实事件流当回归基准，断言帧数与字节上界）。

**item 的最终形态只在 `items.ts` 定义一处。** 流式路径把 delta 攒起来，消息落盘时把 entry 过一遍
同一个 `itemsFromEntries()` 再发 `set`——两条路径因此不可能漂移。而且落盘文本与流式文本一致时
**不重发**（`needsResend`），所以正常路径下每段内容只过线一次；只有 retry / 改写这类真漂移才补发。

**工具调用是独立的顶层 item**，不嵌在 assistant 消息里。这样 `patch` 永远只寻址一个 id，不需要
路径进数组；调用↔结果的配对也留在服务端（它有整棵树）。客户端按相邻关系把它们画到一起。

`tool_execution_update` 的 `partialResult` 是**累积**的（每次重发全部输出）。translate 只发增量，
否则长命令是 O(n²) 的流量。

**帧数由回合时长决定，不由字数**：≈ 时长 / `FLUSH_MS`。实测一篇 2000 字回答流式 170 秒 = 1042 帧
/ 198KB，平均每帧 191 字节里只有约 22 个字符是内容——信封（`sessionId` 36 字符 + `seq` + `id` +
字段名）占了大头。**这个比例难看但绝对量无所谓**（1.2 KB/s，LAN 上什么都不是），而想改善它只能提高
攒批阈值，那会直接牺牲流式的顺滑感。所以 `FLUSH_MS = 80` 保持不动——真要动，先想清楚是在拿可感知
的延迟换一个不影响任何东西的带宽数字。

（对比：v1 同一篇回答约 970KB——每个 token 一帧，外加 `message_end`/`turn_end`/`agent_end` 各带
一份 22K 字符的全文。）

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

`items.ts` 处理全部五种：超长文本截断成 `{s, more:{ref, bytes}}`，图片一律只留占位。**句柄
（ref）对客户端不透明**，回传给 `GET /sessions/:id/full?ref=` 取原文——这让原先
`{entryId, part, index}` 三元组和两侧镜像的 `FullPart` 枚举收成了一个字符串。

**注意 `toolCall.arguments`**：按字段逐个截断，不整体丢弃——`write` 把整个文件塞进 `content`，
但 UI 需要旁边的 `file_path` 才能渲染出「▸ write src/foo.ts」（`headline()`）。

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

### 扩展命令不产生 agent_settled —— 所以忙碌状态由服务端说，不由客户端推断

普通 prompt 走 `agent_start … agent_settled`。**扩展命令（`/askme` 之类）不走**——它就地执行，
只发出 handler 产生的消息。实测：只有一条 `custom` 消息，`content` 还是空的（`hasUI === false`
时它走非交互路径），且**整个会话文件都不存在**——那条消息根本没落盘。

旧设计把生命周期事件转给客户端，于是客户端要靠启发式（「不能只等 `agent_settled`」）才不会永远
转圈。现在 `status.running` 由服务端从 `session.isStreaming` 的变化推出：扩展命令从不把它置真，
也就没有需要清的转圈状态。`e2e2.mjs` 的 A 项断言的就是「从未被标记为运行中」。

扩展产出的 `custom` 消息由 translate 直接从事件产出一条 notice（铸造 id，不落盘、resync 后消失
——诚实，因为磁盘上没有可恢复的东西）。旧 wire 上这条内容根本到不了聊天里。

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
`reload()` 把 listener 集合搬到新 agent 上，然后调 `onResync()`（由 `ws.ts` 注册）给每个订阅者发
一条新的 `hello`——新 agent 的 seq 从 0 开始，客户端的游标已经没有意义。

这里的分工是刻意的：pool 不知道怎么拼 `hello`（那要读 detail 和首屏），`ws.ts` 知道。所以 pool 只
喊一声，不去 import 读路径。

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
`GET /items` 就看不到外部那条消息，而文件本身明明有。`e2e2.mjs` 的 E 项就是抓这个的，第一版
实现直接被它挡下来了。

代价是每次读多一次 `statSync`（约 0.04ms）。

### 新建会话在首次写入前不存在于磁盘

`SessionManager.create()` 延迟到第一条 entry 才落盘，期间任何目录扫描都看不到它。
`sessions/scan.ts` 的 `pending` 表登记这类会话，让 `locate()` 和 `summaries()` 都能看到，避免
客户端拿到 id 却立刻 404。真实文件被扫到后自动清除，中间不会出现重复的一行。

### hello 的快照必须与 seq 在同一个 tick 里取

`sendHello()` 里从 `snapshotPoint()` 到 `send()` 之间**没有 await**，这是刻意的：flush、序号、
item 列表必须描述同一个瞬间。中间夹一个 await，那期间落盘的 entry 会既不在快照里、也不在之后
释放的推送里，消息就凭空消失。`itemPageOf()` 就是为此从 `getItemPage()` 里劈出来的同步版本。

另一半是订阅时机：监听器要在**建快照之前**挂上（否则期间的推送会丢），但挂上后先**攒住**不发，
等快照发完再按 `seq > 快照序号` 释放。不攒的话，一个跟订阅赛跑的 prompt 会让 user 消息既被快照
的 flush 发出去、又包含在快照里，界面上出现两条——实测踩到过。

### getContextUsage() 不能每个事件都调

它内部是 `getBranch()` + `estimateContextTokens(整条分支的 messages)`，实测 1460 条 entry 的会话
上 **1.96ms/次**。第一版在 `handle()` 末尾对**每个** SDK 事件调 `syncStatus()`，而 delta 也是事件
——一个长回合 8000 个 delta = **15.6 秒纯 CPU**，全压在事件处理路径上、挡在每次 flush 前面。
`sameStatus` 只去重了「要不要发」，没去重「要不要算」。

所以 `StatusProbe` 拆成两半：`running()` 是字段读（`_isAgentRunActive`），每个事件都读；
`context()` 只在 `entry_appended` / `agent_settled` / `compaction_end` 时读——delta 改不了已落盘的
上下文。`snapshotPoint()` 也读一次，因为新订阅者要把上下文条填上。

`translate.test.ts` 里钉了一条：整份 trace（100+ 个 delta）只允许 1 次 estimate。

### 断线补齐是「重发整条 item」，不是重放错过的推送

`LiveAgent.touched` 是 `itemId → 它最后一次变化时的 seq`。重连时 `catchUpIds()` 挑出
`seq > 游标` 的那些 id，`ws.ts` 把每条 item 按当前状态整条重发（`add` 在客户端是 **upsert**）。

这取代了一个 200 条的推送环形缓冲，因为那个缓冲**根本不够用**：实测一篇 2000 字的回答流式
**1042 个推送**（帧数 ≈ 回合时长 / 80ms，跟字数关系不大），任何长回合中途重连都会掉出缓冲、退化
成全量快照。而「这几条 item 现在长这样」既比「你错过的 1042 个推送」小，也不需要任何增量算术
——一百次 append 加一个 set 折叠成它的当前状态就完了。

内存上也更省：每条 item 一个数字，覆盖整个会话；缓冲是 200 份完整 payload，只覆盖 0.2 个长回合。

两个要点：

**补齐帧共享同一个 seq**（快照的那个），因为它们的语义是「截至该序号，这些 item 长这样」。它们是
重建而非重放，所以 `live.seq` 故意不推进。

**有 id 解析不出来就退回快照。** 那意味着这条 item 已经不在活跃分支上，或者是上一世 agent 铸的
`live-N`。宁可发一次快照，也不能让客户端悄悄留着服务端已经没有的东西。

**但铸造 id 必须先解析，不能直接当解析不出来。** 流式消息以 `live-N` 加进客户端，客户端就一直用
这个 key；落盘后服务端列表里是真实 entry id。不做映射的话，「断线在回合中、重连在回合后」这个最
常见的场景每次都会退化成全量快照。`Translator.resolve()` 提供 `live-N → entry id`，而重发时**盖回
客户端认识的那个 id**——否则同一条消息在客户端会存两份（一份过期、一份新的）。也不能简单跳过
`live-N`：客户端手里那份是流式中途的快照，缺 usage、还标着 pending，跳过就永远停在转圈状态。

安全性来自时序：只有收到过原始 `add live-N` 的客户端才可能让它变 stale。回合结束后才订阅的客户端
拿到的是 `hello`（里面是 entry id），而 `hello` 的序号已经晚于落定，所以 `live-N` 不会进它的补齐集合。
`e2e2.mjs` 的 F 项就是抓这个的——把 `resolve` 改回恒等，三条断言全红。

### 游标超前于 agent 时要重发快照

agent 空闲 10 分钟被回收后重建，seq 从 0 开始，而客户端还拿着上一世的游标。早先把这种情况当
「没什么要补的」，结果是界面停在回收前的历史上，而且**无法得知**这期间外部 pi TUI 改过会话。
现在 `catchUpIds()` 对 `sinceSeq > currentSeq` 返回 `undefined` → 发 `hello`。代价很低（一次缓存
命中的解析，约 3ms），换掉的是一个会静默显示过期内容的洞。

### 展示要用 buildContextEntries 而非 getEntries

会话是树。`getEntries()` 会把废弃分支也交出来。`buildContextEntries()` 给的是当前活跃分支并已
应用 compaction，跟 TUI 看到的一致。

### 两个 ThinkingLevel

pi-ai 的 `ThinkingLevel` **不含** `"off"`（那个叫 `ModelThinkingLevel`）；pi-agent-core 的含。
`AgentSession.setThinkingLevel()` 要的是后者。`protocol.ts` 里镜像了后者。

### HttpError 不用构造函数参数属性

`node --experimental-strip-types` 只擦类型不转换代码，参数属性（`constructor(readonly x: T)`）
会让 `node --test` 直接语法报错。字段显式赋值。

## dsh 后端的坑

### 两条下行 socket 之间没有顺序

`/api/events.mux`（内容）和 `/api/events.host`（running 翻转）是**两条独立的 WebSocket**。
最早的实现在 `host/session-status(running:false)` 上清理流式状态，结果 `running:false` 抢在
mux 的 `assistant/message` 前面到达，那条消息就被当成「没有在流的消息」重新 `add` 了一次——
推流比落盘多出一条 assistant。凡是要和内容对齐的动作，都必须挂在 mux 自己的事件上
（现在是 `turn/end`）。

### 快照必须同步，所以日志得先预热

`hello` 要在同一个 tick 里读 item 页和 seq（见 `store.ts` 的注释和 `backend.ts` 的
`SessionHandle`）。pi 靠的是缓存好的同步解析；dsh 的历史是 RPC，所以 `acquire()` 里先
`logs.load()` 把整份日志拉进内存，`open()` 之后的 `itemPage()` 一个 await 都不能有。

`ws.ts` 在 `acquire` 之后就挂监听器，所以拉取期间会有事件到达——`SessionLogs` 为此留了一个
pending 缓冲，拉完再按 seq 合并进去。不做这件事的后果不是丢推送（推送照发），而是**服务端的
日志比客户端少一条**，下次补齐会把过期的版本发回去。

### 断线就是重来

harness 的 mux 接受 `since` 但**忽略**它（v1 未实现），重连后不重放任何东西。所以掉线一律
`logs.clear()` + 对每个订阅者触发 resync——`hello` 本来就是干这个的。

### 只读，但会话得先「存在」

`session.history` 明确不会 resume/publish agent，所以浏览是免费的。但 `session.list` **不**过滤
归档会话（归档是 workspace 注册表的事），app 的滑动删除映射到 `workspace.archiveSession`，
所以归档集合要自己维护：启动时 `workspace.list` 拿基线，之后靠 `host/archived-sessions-changed`
和 `archiveSession` 的返回值更新。不过滤的话，删除在手机上看起来就是没反应。

### 不读 `request/header`

它带着整份拼好的 system prompt——实测**单条 38KB**。provider/model 用 `request/context`（3 个
字段）就够；只有 `reasoningEffort` 必须从 header 的 `config` 里取，而且它写在同一次请求的
`request/context` **之前**，按顺序折叠会每次都丢掉。

### 不存 `assistant/chunk`

实测一个会话 2589 条事件里 2521 条是 chunk。它们不产生 item，后面的 `assistant/message` 带着
同样的文本，所以内存日志直接跳过它们。也因此 `session.history` 的分页几乎没用：`maxMessages: 5`
要 483KB，整个会话也才 667KB——一页必须带上它覆盖的消息的全部 chunk 事件。索性一次拉完整份，
和 pi 一样从完整列表里切页（工具调用和它的结果是两条事件，切在中间会让调用行永远转圈）。

### 注入的 user/message 不是人说的话

harness 把运行时快照、skill 目录、各级 AGENTS.md 都作为 user 角色的消息喂给模型，实测每轮真
prompt 周围有三条。只有 `source.kind` 能区分（`user` / `user-rpc` 是人，其余不是）。不过滤的话
对话会被机器话淹没。

### 审批和提问只能拒

protocol 2 的 `Item` 没有可交互卡片，手机上没法弹「允许吗」。但**不答复 agent 会永久等**——
所以 `approval/requested` 一律答 `rejected`（必须是 `ok:true` + `outcome:'rejected'`；
`ok:false` 会被当成 malformed，什么都不解除），`question/requested` 用 `ok:false` +
`code:'cancelled'` 取消。两者都往对话里插一条 notice，让用户知道发生过、可以去 dsh 的 web UI 处理。

### 手抄的线协议

`backends/dsh/wire.ts` 是手抄的，不是 import 的。`@deepseek-ai/dsh-host-apiproxy/api` 确实发在
npm 上（注意 `latest` tag 指向的比 `next` 旧），但它 type-only 依赖另外七个 `@deepseek-ai/dsh-*`
包，而 harness 自己声明是会破坏兼容的 developer preview。真正的约束是：bridge 得能对付用户实际
跑的那个版本，所以运行时无论如何都要防御。手抄的部分全部是 optional 或宽类型，缺字段一律当
「这个版本不报这个」处理。

## API

见 `protocol.ts`（wire 类型）和 `index.ts`（路由注册）。所有请求要求
`Authorization: Bearer <token>`；WS 用 `?token=` 查询参数。

`cwd` 作为查询参数一律 base64url 编码。

**没有 `GET /sessions/:id`。** 会话设置随 WS 的 `hello` 一起到，运行状态靠 `status` 推送。开一个
会话因此是 1 个 WS 帧，而不是两次 HTTP 往返 + 3 次全量解析。写接口（`PATCH`、`/title`）直接返回
新的 detail，客户端不再跟一个 GET。

订阅带 `sinceSeq`（客户端从收到的推送里记下的游标）。带了就走增量补齐，没带或者对不上就发 `hello`。

`PROTOCOL` 常量是 wire 版本，由 `/ping` 报出。客户端在保存连接前比对，不匹配就明说要升级哪一端
——改动 wire 形状时记得 bump，并用 `test/capture.mjs` 重抓 Kotlin 侧的回放 fixture。
