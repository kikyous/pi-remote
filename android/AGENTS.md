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

### 每会话一个 store，靠 epoch 防串台

没有全局的「当前会话」对象。`AppRepository.storeFor(id)` 给每个会话一个 `SessionStore`
（LRU 保 5 个），切换只是让 UI 订阅另一个 flow。每次 `refresh()` 递增 `epoch`，在途响应回来
时 epoch 对不上就丢弃。`App.kt` 里再用 `key(sessionId)` 强制重建 composition。

实测：在 984 条的长会话和 6 条的短会话之间 400ms 间隔切换 20 次，内容零串台。

### 反向列表 + 尾部优先分页

`LazyColumn(reverseLayout = true)`，数据传 `items.asReversed()`。首屏只取尾部 50 条，滚到
顶部附近用 `oldestId` 作游标续拉。`SessionStore.MAX_ITEMS = 400` 是内存上限。

实测：打开 2.7MB / 984 条的会话，Java heap 稳定在 12–14MB。

### JSON 取值一律用安全转换

`kotlinx.serialization` 的 `jsonPrimitive` 扩展在遇到对象或数组时**抛异常**，不是返回 null。
`content` 字段既可能是字符串也可能是数组，直接 `.jsonPrimitive` 会当场崩。全部走
`(this[key] as? JsonPrimitive)?.contentOrNull`，见 `ChatItem.kt` 底部的 `str/int/bool`。

同理，entry 保持 `JsonObject` 而非解析成封闭类层次——pi 以后加新 entry 类型时，未知类型应该
被跳过，而不是让整屏崩掉。

## 扫码连接（CameraX + ML Kit，全 Compose）

服务端启动横幅打印二维码，`ConnectScreen` 的「扫码连接」直接渲染 `QrScannerScreen`（Compose 全屏页，不是独立 Activity）：`PreviewView`（TextureView，`COMPATIBLE` 模式）预览 + `ImageAnalysis`（`KEEP_ONLY_LATEST`）逐帧送 ML Kit `BarcodeScanning`，取景框用四块半透明面板围出来的真实窗口。

关键点：
- **相机权限运行时申请**：扫码按钮点击时 `checkSelfPermission`，未授权就 `RequestPermission` launcher；之前 zxing 的 `CaptureActivity` 会自己弹权限框，现在没有这个兜底了。
- **首次命中即停**：`AtomicBoolean` 防重复回调；`DisposableEffect` 里 `unbindAll()` + `clearAnalyzer()` + `shutdown()` 分析线程，避免返回后相机还亮着。
- **为什么不用 zxing**：库自带 `CaptureActivity` 在它 manifest 里锁死 `sensorLandscape`，且 `setOrientationLocked(false)` 时 zxing 根本不碰方向（反编译 `CaptureManager.initializeFromIntent` 确认），manifest 横屏声明永远赢——怎么调都横屏。换成自己管 CameraX 后方向天然跟随设备。
- **ML Kit 用 17.2.0（bundled 模型）**：不依赖 Google Play 服务，国产 ROM 平板也能用；代价是 APK 大约 +2.5MB。
- **payload 解析器不能用 `android.net.Uri`**：JVM 单元测试里 `Uri.parse` 抛 `Stub!`。`QrConnect.kt` 是纯 Kotlin 手写解析（`split('&')` + 自写 `%XX` 解码），可单测。

## 与服务端的约定

`net/Protocol.kt` 镜像 `server/src/protocol.ts`，改动要两边一起改。

被服务端截断的内容带 `{truncated, part, index, fullLength}`，点「展开全部」走
`/entries/{id}/full` 取原文。图片一律只传占位（原图最大实测 361KB）。

**loading 状态不能只依赖 `agent_settled`**：扩展命令（`/xxx`）不产生生命周期事件，只会发出
handler 自己的消息。详见服务端 `AGENTS.md`。
