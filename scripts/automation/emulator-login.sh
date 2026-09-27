#!/usr/bin/env bash
# 模拟器上免登录启动商家端 App（ADR-027）。
#
#   scripts/automation/emulator-login.sh <店主 user_no> [设备序列号，默认 emulator-5554]
#
# 本机私钥签一张 60 秒一次性票据，adb 写进 App 私有目录的 _doc/automation-ticket.txt，
# 冷启动 App：它读到就删掉文件、换成会话、进工作台。票据不打印。
#
# 为什么走文件不走启动参数：离线 SDK 5.24 的 plus.runtime.arguments 只从 uni 小程序模式的
# Intent extra（unimp_run_arguments）取值，普通 App 冷启动带 extra 它不读 —— 第一版就栽在这，
# 两种 key 都试过、服务器一条请求都没收到。
set -euo pipefail
SUB="${1:?用法：emulator-login.sh <店主 user_no> [序列号]}"
SERIAL="${2:-emulator-5554}"
ADB="${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
PKG=top.hxmall.bapp
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APPID="$(python3 -c "import json,re,sys; s=open('$ROOT/b-app/src/manifest.json',encoding='utf8').read(); s=re.sub(r'/\*.*?\*/','',s,flags=re.S); print(json.loads(s)['appid'])")"
DEST="/sdcard/Android/data/$PKG/apps/$APPID/doc/automation-ticket.txt"

TMP="$(mktemp)"; chmod 600 "$TMP"; trap 'rm -f "$TMP"' EXIT
python3 -c "import sys; sys.path.insert(0, '$HERE'); from _ticket import sign_ticket; sys.stdout.write(sign_ticket('B', '$SUB'))" > "$TMP"
launch() { "$ADB" -s "$SERIAL" shell am start -n "$PKG/io.dcloud.PandoraEntry" >/dev/null; }
DIR="$(dirname "$DEST")"
# 新装的 App 还没跑过时，它的私有目录不存在；新版 Android 上 adb 建不了别的 App 私有区里的目录
# （secure_mkdirs: Operation not permitted）。先让 App 自己跑一次把目录建出来。
if ! "$ADB" -s "$SERIAL" shell ls "$DIR" >/dev/null 2>&1; then
  launch
  for _ in $(seq 1 20); do "$ADB" -s "$SERIAL" shell ls "$DIR" >/dev/null 2>&1 && break; sleep 1; done
fi
"$ADB" -s "$SERIAL" shell am force-stop "$PKG"
"$ADB" -s "$SERIAL" push "$TMP" "$DEST" >/dev/null
rm -f "$TMP"
# 用 am start 不用 monkey：monkey 在这台模拟器上时灵时不灵（点了图标式的启动常常没把 App 拉到前台）
launch
echo "已写入票据并冷启动 $PKG（$SERIAL），几秒后应进入工作台"
