# Pi Remote

用手机 / 平板远程控制局域网内 PC 上的 pi coding agent。

Android 原生 app（Kotlin + Jetpack Compose）+ PC 端 Node 桥接服务。

## 跑起来

**PC 端**

```bash
cd server
npm install
npm start          # 监听 0.0.0.0:30150
```

启动时会打印地址和 token：

```
pi-remote-server 0.1.0
  URL:   http://192.168.31.117:30150
  Token: 85Ou5U44v-lN0BckrE6QJ5OuMgBAekZQ
  Listening on all interfaces — only use this on a trusted network.
```

Token 存在 `~/.pi/remote/token`，首次启动自动生成。

**手机端**

```bash
cd android
./gradlew installDebug        # 装到已连接的设备
```

APK 产物在 `android/app/build/outputs/apk/debug/app-debug.apk`，也可以直接拷到手机安装。

首次打开填地址和 token（地址可以只写 `192.168.31.117`，端口会自动补 30150）。点「连接」会先验证再保存。

> 构建需要 JDK 17。本机的在 `~/tools/jdk-17.0.20+8`，已写在 `android/gradle.properties`
> 的 `org.gradle.java.home`。换机器改那一行。

## 能做什么

- 按工作目录浏览会话，打开任意一个看完整历史
- 发消息、看流式回复、看工具调用和输出、随时中止
- 新建会话；每个会话独立切换模型和思考等级
- agent 在跑的时候可以切后台，跑完收通知
- 手机单栏、平板双栏
- 手机和平板可以同时连，一端发的消息另一端实时看到

MVP 不含：图片附件、扩展弹框转发、文件浏览 / git diff、fork 与分支导航、手动 compact、
mDNS 自动发现、公网访问。

## 结构

```
server/     Node + TypeScript 桥接服务   见 server/AGENTS.md
android/    Kotlin + Compose             见 android/AGENTS.md
plan.md     设计与取舍
```

两份 `AGENTS.md` 记了实测数据和踩过的坑，改代码前值得先看。

## 架构一句话

**浏览走文件、执行才起 agent。** 会话列表和历史直接读 `~/.pi/agent/sessions/` 下的 JSONL，
不创建 AgentSession，所以切换会话没有任何启动开销；只有真正发消息时才为该会话创建 agent，
空闲 10 分钟回收。

pi 本身没有任何网络能力（只有 stdin/stdout 的 RPC 模式和 Node SDK），所以 PC 端这个服务是必需的。
