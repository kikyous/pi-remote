#!/usr/bin/env bash
#
# adb 无线调试配对：生成配对二维码 → 监听配对服务 → 自动 adb pair + connect。
#
# 流程（对应 Android "开发者选项 → 无线调试 → 使用二维码配对设备"）：
#   1. 电脑端生成二维码 WIFI:T:ADB;S:<name>;P:<password>;; 并显示在屏幕上
#   2. 平板上用无线调试的扫码器扫这个码（不是普通相机）
#   3. 平板随即广播 _adb-tls-pairing 服务，脚本检测到后自动 adb pair
#   4. 配对成功后自动 adb connect 到 _adb-tls-connect 端口
#
# 用法：
#   ./pair-qr.sh                 # 默认服务名 adb-pi-remote，输出 /tmp/adb-pair-qr.png
#   ADB_PAIR_NAME=my-pad ./pair-qr.sh /path/to/qr.png

set -euo pipefail

# 本机 adb 在 /usr/local/bin，但 pi 的执行环境 PATH 很精简，显式补上
case ":$PATH:" in
  *":/usr/local/bin:"*) ;;
  *) export PATH="/usr/local/bin:$PATH" ;;
esac

NAME="${ADB_PAIR_NAME:-adb-pi-remote}"
OUT="${1:-/tmp/adb-pair-qr.png}"
TIMEOUT="${ADB_PAIR_TIMEOUT:-120}"   # 等待扫码的秒数

# ---- 找一个带 qrcode 的 python（装到哪个版本不确定，逐一探测） ----
PY=""
for p in /usr/local/bin/python3.11 /usr/local/bin/python3.13 /usr/local/bin/python3.10 /usr/local/bin/python3 /usr/bin/python3; do
  if [ -x "$p" ] && "$p" -c "import qrcode" >/dev/null 2>&1; then PY="$p"; break; fi
done
if [ -z "$PY" ]; then
  echo "未找到 qrcode 模块，尝试安装…"
  for p in /usr/local/bin/python3.11 /usr/local/bin/python3.10 /usr/local/bin/python3 /usr/bin/python3; do
    [ -x "$p" ] || continue
    # 直连 PyPI 在本机经常断，先走国内镜像
    if "$p" -m pip install --quiet qrcode pillow -i https://mirrors.aliyun.com/pypi/simple/ 2>/dev/null \
      || "$p" -m pip install --quiet qrcode pillow 2>/dev/null; then
      PY="$p"; break
    fi
  done
fi
if [ -z "$PY" ]; then
  echo "错误：找不到可用的 python3（需要 qrcode 模块，pip 安装也失败）" >&2
  exit 1
fi

# ---- 生成随机 6 位配对码 + 二维码 ----
PASSWORD=$($PY -c "import secrets; print(f'{secrets.randbelow(1000000):06d}')")
CONTENT="WIFI:T:ADB;S:$NAME;P:$PASSWORD;;"

$PY - "$CONTENT" "$OUT" <<'PYEOF'
import sys
import qrcode

content, out = sys.argv[1], sys.argv[2]
qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=12, border=4)
qr.add_data(content)
qr.make(fit=True)
qr.make_image(fill_color="black", back_color="white").save(out)
print(f"二维码已生成: {out}")
PYEOF

echo "=============================="
echo "配对码: $PASSWORD   （adb pair 时使用）"
echo "二维码: $OUT"
echo "=============================="
echo "请在平板上操作：设置 → 开发者选项 → 无线调试 → 使用二维码配对设备 → 扫描屏幕上的二维码"
echo "等待配对服务（最多 ${TIMEOUT}s）…"

# 显示二维码：macOS 用 open，Linux 用 xdg-open
if command -v open >/dev/null 2>&1; then open "$OUT"
elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$OUT"
else echo "请手动打开二维码图片: $OUT"; fi

# ---- 等待平板广播 _adb-tls-pairing 并配对 ----
PAIRED=""
for i in $(seq 1 "$TIMEOUT"); do
  # 取最后一列 host:port（服务名可能带空格/序号后缀，固定列号会取错）
  LINE=$(adb mdns services 2>/dev/null | grep "_adb-tls-pairing" | head -1 || true)
  if [ -n "$LINE" ]; then
    HOSTPORT=$(echo "$LINE" | awk '{print $NF}')
    echo "检测到配对服务: $HOSTPORT"
    if adb pair "$HOSTPORT" "$PASSWORD"; then
      PAIRED="yes"
      break
    else
      echo "配对失败，重试中…"
      sleep 2
    fi
  fi
  sleep 1
done

if [ -z "$PAIRED" ]; then
  echo "超时：未检测到配对服务。检查：平板和电脑是否同一局域网？macOS 防火墙是否放行 adb/python？" >&2
  exit 1
fi

# ---- 配对成功：自动 connect 到 _adb-tls-connect 端口 ----
echo "配对成功，连接 adb…"
CONNECT_LINE=$(adb mdns services 2>/dev/null | grep "_adb-tls-connect" | head -1 || true)
if [ -n "$CONNECT_LINE" ]; then
  HOSTPORT=$(echo "$CONNECT_LINE" | awk '{print $NF}')
  adb connect "$HOSTPORT"
fi

sleep 1
echo "设备列表："
adb devices -l
