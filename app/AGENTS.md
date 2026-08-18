# Pi Remote App — 开发笔记

```bash
./gradlew assembleDebug     # 产物 composeApp/build/outputs/apk/debug/
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

## JDK

Android 构建需要 JDK 17+（Gradle 9.7 要求），但系统 asdf 默认是 JDK 8，直接跑
`./gradlew` 会报 `Gradle requires JVM 17 or later`。本机 JDK 17 在
`~/tools/jdk-17.0.20+8/Contents/Home`（**不在** `/Library/Java/`），构建前：

```bash
export JAVA_HOME=~/tools/jdk-17.0.20+8/Contents/Home
./gradlew :composeApp:assembleDebug        # 构建 debug APK
./gradlew :composeApp:installDebug         # 构建并安装到已连接设备
```

无线 adb 部署到平板（示例）：

```bash
S=adb-AUUEUT4517002378-OF717s._adb-tls-connect._tcp.
adb -s $S install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```
