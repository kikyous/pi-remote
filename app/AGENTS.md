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
