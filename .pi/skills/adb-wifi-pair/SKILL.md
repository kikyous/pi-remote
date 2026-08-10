---
name: adb-wifi-pair
description: 生成 Android 无线调试（adb over Wi-Fi）的配对二维码，引导用户在平板上扫码，并自动完成 adb pair / adb connect。当用户需要无线安装 APK、用 adb 连接 Android 设备（尤其平板）时使用。
---

# ADB 无线调试配对（二维码）

Android 11+ 的无线调试支持「使用二维码配对设备」：**平板扫电脑屏幕上显示的二维码**即可配对，不需要手动输入 6 位配对码。本技能自动化整条链路：生成二维码 → 等待扫码 → 自动 `adb pair` → 自动 `adb connect`。

## 前提

- 平板已开启：设置 → 开发者选项 → **无线调试**（打开，保持该页面可见）
- 平板和电脑在**同一局域网**
- 电脑有 `adb`（`adb version` 可用）
- 依赖 `python3` + `qrcode`（脚本首次运行会自动 `pip3 install qrcode pillow`）

## 使用

直接运行脚本（在技能目录内用相对路径）：

```bash
./scripts/pair-qr.sh
```

自定义服务名或输出路径：

```bash
ADB_PAIR_NAME=my-pad ./scripts/pair-qr.sh /tmp/pair-qr.png
```

脚本会：

1. 生成随机 6 位配对码 + 二维码 `WIFI:T:ADB;S:<name>;P:<password>;;`，并在屏幕上打开二维码图片
2. 等待用户操作平板：**无线调试 → 使用二维码配对设备**（相机取景器）→ 扫描屏幕上的二维码
3. 检测到 `_adb-tls-pairing` 服务后自动 `adb pair <ip:port> <password>`
4. 配对成功后自动 `adb connect` 到 `_adb-tls-connect` 端口，并打印设备列表

## 执行者注意事项

- **必须先跑脚本**，不要手动编造二维码内容——配对密码是脚本生成的随机数，`adb pair` 必须用同一个密码
- 脚本是阻塞式的（默认等 120s），提示用户扫码后保持等待
- 如果脚本报「未检测到配对服务」：
  - 确认平板点的是「使用**二维码配对设备**」（它会打开相机），而不是「使用配对码配对设备」
  - macOS 防火墙可能拦截 adb 的 mDNS：系统设置 → 网络 → 防火墙 → 允许 `adb`/`Python` 入站
  - 配对模式有时限，二维码失效就重跑一次脚本
- 配对成功后设备在 `adb devices` 里显示为 `ip:port  device`；之后安装 APK 直接 `adb -s <ip:port> install -r app-debug.apk`

## 已知限制

- 一次只能配对一台设备（配对码一次有效，二维码过期需重新生成）
- 需要平板进入「使用二维码配对设备」界面才会广播配对服务，脚本只是等待它出现
