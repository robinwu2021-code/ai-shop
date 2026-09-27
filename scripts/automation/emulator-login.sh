#!/usr/bin/env bash
# 模拟器上免登录启动商家端 App（ADR-027）。
#
#   scripts/automation/emulator-login.sh <店主 user_no> [设备序列号，默认 emulator-5554]
#
# 本机私钥签一张 60 秒一次性票据，冷启动 App 时经启动参数交进去；App 换成会话后直接进工作台。
# 票据只在这条管道里经过，不打印。
set -euo pipefail
SUB="${1:?用法：emulator-login.sh <店主 user_no> [序列号]}"
SERIAL="${2:-emulator-5554}"
ADB="${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
PKG=top.hxmall.bapp
HERE="$(cd "$(dirname "$0")" && pwd)"

TICKET="$(python3 -c "import sys; sys.path.insert(0, '$HERE'); from _ticket import sign_ticket; print(sign_ticket('B', '$SUB'))")"
"$ADB" -s "$SERIAL" shell am force-stop "$PKG"
# DCloud 的启动参数走 Intent extra「arguments」—— App 里 plus.runtime.arguments 读到的就是它
"$ADB" -s "$SERIAL" shell am start -n "$PKG/io.dcloud.PandoraEntry" --es arguments "$TICKET" >/dev/null
unset TICKET
echo "已用票据冷启动 $PKG（$SERIAL），几秒后应进入工作台"
