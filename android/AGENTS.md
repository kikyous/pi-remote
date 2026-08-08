# Pi Remote Android — 开发笔记

```bash
./gradlew assembleDebug     # 产物 app/build/outputs/apk/debug/
./gradlew installDebug      # 装到已连接的设备/模拟器
```

## 工具链（这台机器上的实际情况）

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | Temurin 17.0.20 | `~/tools/jdk-17.0.20+8`（**不在** `/Library/Java/`） |
| Gradle | 8.7 | wrapper，已缓存在 `~/.gradle/wrapper/dists` |
| AGP | 8.5.2 | |
| Kotlin | 1.9.24 | Compose Compiler 必须配对 1.5.14 |
| compileSdk | 34 | build-tools 34.0.0 |

**JDK 路径写在 `gradle.properties` 的 `org.gradle.java.home`**。系统默认 `java` 是 JDK 1.8，
不指定就构建不了。换机器时改这一行或删掉（若 `JAVA_HOME` 已是 17+）。

踩过的坑，避免重走：

- `/usr/libexec/java_home` 找不到任何 JDK；`brew list` 里没有；`/Library/Java/JavaVirtualMachines`
  是空的。**JDK 17 在 `~/tools`**，靠 `~/.gradle/daemon/8.7/*.log` 里的 `javaHome=` 才找到。
- PyCharm 自带的 JBR 是 **JDK 11 且缺 `jlink`**（精简运行时）。compileSdk 34 的
  `JdkImageTransform` 需要 jlink，所以 JBR 不能用来构建。
- 降级到 AGP 7.4 走 JDK 11 的路线也堵死：本机 `platforms/` 只有 android-28/29/34，Compose 需要
  compileSdk 31+，而 34 又要 jlink。没有 `cmdline-tools`，装不了别的 platform。
- `services.gradle.org` 在这台机器上会断流（实测下到 ~14MB 就中断）。需要下 Gradle 时用
  `https://mirrors.cloud.tencent.com/gradle/`。

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

## 与服务端的约定

`net/Protocol.kt` 镜像 `server/src/protocol.ts`，改动要两边一起改。

被服务端截断的内容带 `{truncated, part, index, fullLength}`，点「展开全部」走
`/entries/{id}/full` 取原文。图片一律只传占位（原图最大实测 361KB）。

**loading 状态不能只依赖 `agent_settled`**：扩展命令（`/xxx`）不产生生命周期事件，只会发出
handler 自己的消息。详见服务端 `AGENTS.md`。
