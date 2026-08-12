# Pi Remote Android — 开发笔记

```bash
./gradlew assembleDebug     # 产物 app/build/outputs/apk/debug/
./gradlew installDebug      # 装到已连接的设备/模拟器
```

## 工具链（这台机器上的实际情况）

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | Temurin 17.0.20 | `~/tools/jdk-17.0.20+8`（**不在** `/Library/Java/`） |
| Gradle | 9.7.0 | wrapper，腾讯镜像下载（`services.gradle.org` 会断流） |
| AGP | 9.3.1 | |
| Kotlin | 2.4.10 | Compose 编译器随 Kotlin 2.0+ 插件化 |
| compileSdk | 37 | build-tools 36（AGP 自动下载，许可证已接受） |
| Compose | 1.11.4（BOM 2026.06.01） | |

**JDK 通过 `JAVA_HOME` 环境变量指定**（`~/.zshrc` 里已 export 到 `~/tools/jdk-17.0.20+8`）。
系统默认 `java` 仍是 JDK 1.8，但 gradlew 会优先用 `JAVA_HOME`。不要在 `gradle.properties`
里写 `org.gradle.java.home`——那是本机绝对路径，会弄坏 CI 和其他机器。
在 IDE 里构建时，记得把 Gradle JDK 设为 17（IDE 不会读 `~/.zshrc`）。

**AGP 9 的两个开关**（`gradle.properties`）：
- `android.newDsl=false`：AGP 9 默认开新 DSL（`android.newDsl=true`），经典 KGP
  （`org.jetbrains.kotlin.android`）与新 DSL 不兼容直接报错。设 `false` 回到旧 DSL 才能继续用 KGP。
- `android.builtInKotlin=false`：AGP 9 默认内置 Kotlin，与 KGP 链（compose/serialization 插件）冲突。
  官方路线是迁到内置 Kotlin（去掉 kotlin-android 插件）；这里暂时用开关保住现有插件链。
  两个开关 AGP 10 都会移除——到时候要么迁内置 Kotlin，要么升 AGP 前先解决。

踩过的坑，避免重走：

- `/usr/libexec/java_home` 找不到任何 JDK；`brew list` 里没有；`/Library/Java/JavaVirtualMachines`
  是空的。**JDK 17 在 `~/tools`**，靠 `~/.gradle/daemon/8.7/*.log` 里的 `javaHome=` 才找到。
- PyCharm 自带的 JBR 是 **JDK 11 且缺 `jlink`**（精简运行时）。compileSdk 34 的
  `JdkImageTransform` 需要 jlink，所以 JBR 不能用来构建。
- 降级到 AGP 7.4 走 JDK 11 的路线也堵死：本机 `platforms/` 只有 android-28/29/34，Compose 需要
  compileSdk 31+，而 34 又要 jlink。没有 `cmdline-tools`，装不了别的 platform。
- `services.gradle.org` 在这台机器上会断流（实测下到 ~14MB 就中断）。需要下 Gradle 时用
  `https://mirrors.cloud.tencent.com/gradle/`。

## Compose 1.11 升级踩的坑

- **markdown-renderer 0.30 → 0.41+ 拆模块 + 改 API**：`buildMarkdownAnnotatedString` 从
  `utils` 包移到 `annotator` 包，签名从 `content.buildMarkdownAnnotatedString(node, style)` 变成
  `content.buildMarkdownAnnotatedString(node, style, annotatorSettings)`。settings 用 @Composable 的
  `annotatorSettings()`（全参数有默认值，从组合局部读取）。函数是**扩展函数**，不能按顶层函数调用，
  否则报 Unresolved reference。模块拆成 `multiplatform-markdown-renderer`(核心) +
  `-m3`(主题)，MarkdownComponentModel 挪到核心模块的 `compose.components` 包。
- **markdown-renderer 版本和 compileSdk 绑定**：0.43.0 要 compileSdk 37，0.41.0 要 36。
- **依赖版本有 minCompileSdk 门槛**：core-ktx 1.19.0 / lifecycle 2.11.0 要 compileSdk 37；
  compose 1.11.4 只要 35。升 compileSdk 前先查各依赖的 `aar-metadata.properties`。
- **AGP 9 默认内置 Kotlin + 新 DSL**：见上方工具链表，`gradle.properties` 两个开关兜底。
- **AGP 能自动装 SDK 平台**：`~/Library/Android/sdk/licenses/android-sdk-license` 已接受，
  AGP 构建时自动下载缺失 platform/build-tools，不需要 cmdline-tools。

## 三个设计约束

### 每会话一个 store，切换不串台

没有全局的「当前会话」对象。`AppRepository.storeFor(id)` 给每个会话一个 `SessionStore`
（LRU 保 5 个），切换只是让 UI 订阅另一个 flow。推送自带 `sessionId`，路由不看当前哪个屏幕在前台，
所以后台会话照样保持正确。`App.kt` 里再用 `key(sessionId)` 强制重建 composition。

原先还有一个 `epoch` 计数器：`refresh()` 走 HTTP 拉 detail + 首页，在途响应回来时对不上 epoch 就
丢弃。现在 `refresh()` 只是重订阅，权威快照从 `hello` 来，唯一还在飞的请求是翻页——它自己核对
`oldest` 游标有没有被新快照换掉就够了。

实测：在 984 条的长会话和 6 条的短会话之间 400ms 间隔切换 20 次，内容零串台。

### 反向列表 + 尾部优先分页

`LazyColumn(reverseLayout = true)`，数据传分组后再 `asReversed()`（store 保持旧→新，index 0 =
最新 = 视觉底部）。**底部锚定让 stick/滚动补偿机制整个删掉了**：新消息 prepend 到 index 0
自动可见、翻历史时不打扰、流式向上生长、首屏就是最新消息，全部零代码。
展开中间卡片靠 `MaxExpandableHeight = 400.dp` 限高 + 卡片内部滚动（`MessageView.kt`）
控制布局位移，不再需要锚点补偿。首屏 50 条随 `hello` 到，滚到顶部（reverseLayout 下=最高
index 端，分页触发看 `lastVisible`）用 `ChatState.oldest` 作游标续拉。
`MAX_ITEMS = 400`（`ChatState.kt`）是内存上限。

实测：打开 2.7MB / 984 条的会话，Java heap 稳定在 12–14MB。

### 一套模型、一套渲染器，一个纯函数 reducer

服务端推的是 **item**（`net/Protocol.kt` 的 `Item` 密封类），不是 pi 的原始 entry，也不是 SDK
事件。流式中的消息就是 `Item.Assistant(pending = true)`，所以**没有第二套模型**。

早先是两套：`StreamingState` 管流式 + `ChatItem` 管落定，`StreamingBubble` 和
`AssistantBlock`/`ThinkingCard`/`ToolCallRow` 两套渲染器手工保持一致。还有一个 33ms 的 delta 泵
（服务端每个 token 发一帧，客户端得自己攒），以及「相位路由靠每次 flush 保证」这个不变量。全删了
——服务端现在按 80ms/4KB 攒批，`live/coalesce.ts`。

**`ChatState.reduce(push)` 是纯函数**，不碰协程也不碰 client。「客户端会不会收敛到服务端的状态」
因此就是折叠一串 push，`ChatStateTest` 能穷举：重复的 `add`（`hello` 拼装期间在途的推送会造成）、
指向未知 id 的 patch、任意后缀重放、只带 `more` 不带 `s` 的文本 patch。

**`add` 是 upsert，这条是承重的，不是防御性的。** 断线重连时服务端不重放错过的推送（一篇 2000 字
回答要流一千多个），而是把变化过的 item 整条重发——一条 `add` 就把它身上发生过的一切折叠掉。改成
「已存在就忽略」会让补齐静默失效。

**封闭密封类是安全的**，因为 item 的形态由服务端决定：`items.ts` 把每种 pi entry 映射成四种 item
之一，没有对话内容的直接跳过。pi 以后加的 entry 类型到不了客户端，也就崩不了屏——兼容负担挪到了
唯一扛得住的地方。版本错配改由 `/ping` 的 `protocol` 在保存连接前拦住（`WIRE_PROTOCOL`）。

`RealPushStreamTest` 回放一份**从真机服务端抓下来的推流**（`app/src/test/resources/pushes.jsonl`）。
`protocol.ts` 和 `Protocol.kt` 是手工镜像的，改了 wire 形状要用 `server/test/capture.mjs` 重抓，
否则一个改名字段两边都能编译，只会表现为「某些帧被静默丢掉」。

## 扫码连接（CameraX + ML Kit，全 Compose）

服务端启动横幅打印二维码，`ConnectScreen` 的「扫码连接」直接渲染 `QrScannerScreen`（Compose 全屏页，不是独立 Activity）：`PreviewView`（TextureView，`COMPATIBLE` 模式）预览 + `ImageAnalysis`（`KEEP_ONLY_LATEST`）逐帧送 ML Kit `BarcodeScanning`，取景框用四块半透明面板围出来的真实窗口。

关键点：
- **相机权限运行时申请**：扫码按钮点击时 `checkSelfPermission`，未授权就 `RequestPermission` launcher；之前 zxing 的 `CaptureActivity` 会自己弹权限框，现在没有这个兜底了。
- **首次命中即停**：`AtomicBoolean` 防重复回调；`DisposableEffect` 里 `unbindAll()` + `clearAnalyzer()` + `shutdown()` 分析线程，避免返回后相机还亮着。
- **为什么不用 zxing**：库自带 `CaptureActivity` 在它 manifest 里锁死 `sensorLandscape`，且 `setOrientationLocked(false)` 时 zxing 根本不碰方向（反编译 `CaptureManager.initializeFromIntent` 确认），manifest 横屏声明永远赢——怎么调都横屏。换成自己管 CameraX 后方向天然跟随设备。
- **ML Kit 用 17.2.0（bundled 模型）**：不依赖 Google Play 服务，国产 ROM 平板也能用；代价是 APK 大约 +2.5MB。
- **payload 解析器不能用 `android.net.Uri`**：JVM 单元测试里 `Uri.parse` 抛 `Stub!`。`QrConnect.kt` 是纯 Kotlin 手写解析（`split('&')` + 自写 `%XX` 解码），可单测。

## 与服务端的约定

`net/Protocol.kt` 镜像 `server/src/protocol.ts`，改动要两边一起改，并 bump `WIRE_PROTOCOL` /
`PROTOCOL`。

**开一个会话不发 HTTP。** `hello` 一帧就带来首屏 items、会话设置和状态；`GET /sessions/:id` 不存在。
只有往前翻页（`/items?before=`）和取原文（`/full?ref=`）走 HTTP。写接口（改模型、改名、生成标题）
直接返回新的 detail，所以也不跟 GET。

被截断的内容带 `{s, more:{ref, bytes}}`，**ref 对客户端不透明**——原样回传给 `/full?ref=` 即可。
长文本、工具参数、thinking、图片字节全走这一个机制，不再有 `part`/`index` 要镜像。

**忙碌状态看 `status.running`，不要从别处推断。** 服务端从 `session.isStreaming` 的变化推出并推送。
扩展命令（`/xxx`）从不把它置真，所以旧代码里那条「不能只等 `agent_settled`」的启发式没了。

**工具调用是独立的顶层 item**，紧跟在调用它的 assistant 消息之后。`ChatScreen.groupRows()` 按相邻
关系把它们折到一起——这是纯渲染决定。客户端不再做调用↔结果的配对（原先的 `linkToolResults` 要在
整个已加载列表上反复重连，才能处理调用和结果分在不同页的情况）。
