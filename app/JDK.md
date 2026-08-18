# JDK 路径（本地备忘，不入库）

Android 构建需要 JDK 17+（Gradle 9.7 要求），但系统 asdf 默认是 JDK 8，直接跑 gradle 会报：

```
Gradle requires JVM 17 or later to run. Your build is currently configured to use JVM 8.
```

## 本机 JDK 17 路径

```
/Users/chen/tools/jdk-17.0.20+8/Contents/Home
```

## 用法

```bash
cd app
export JAVA_HOME=/Users/chen/tools/jdk-17.0.20+8/Contents/Home
./gradlew :composeApp:assembleDebug        # 构建 debug APK
./gradlew :composeApp:installDebug         # 构建并安装到已连接设备
```

## 部署到平板（adb）

```bash
# 无线 adb 设备（示例）
S=adb-AUUEUT4517002378-OF717s._adb-tls-connect._tcp.
adb -s $S install -r app/build/outputs/apk/debug/app-debug.apk
```
