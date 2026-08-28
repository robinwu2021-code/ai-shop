#!/usr/bin/env bash
#
# 离线包产物体检：拿新打的 APK 和**上一个已知能用的包**逐项对。
#
# 为什么不是「构建成功就行」：这条链路上出过的每一次事故，构建都是成功的 ——
#   · 重解压 DCloud SDK，高德 key 的注入被覆盖没了 → 界面全对，只有定位报错误码 7；
#   · 重解压还会静默清掉应用名与图标 → 装上叫「HBuilder」，图标是默认小机器人；
#   · assets 换了但 dex 没换/换错，`unzip -l` 看不出来（库在不在要数 dex 里的类）。
# 三种都要装到手机上、点到那个功能，才会被发现。
#
# ⚠️ 全篇不用 `grep -q` / `grep -m1` / `head -1` 接在长命令后面：它们命中即关管道，
# 上游 apkanalyzer/aapt2 吃到 SIGPIPE，在 `set -o pipefail` 下整条脚本以 141 退出,
# 而输出看起来像「跑到第一项就正常结束了」—— 写这个脚本时连踩两次。
# 一律先把输出存进变量，再用 awk 单进程挑行。
#
# 用法：
#   b-app/offline/verify-apk.sh <新包> [旧包]
# 不给旧包就只做绝对检查（key / 应用名 / 版本号），不做对比。
set -euo pipefail

NEW=${1:?用法: verify-apk.sh <新包.apk> [旧包.apk]}
OLD=${2:-}
CMDLINE=/opt/homebrew/share/android-commandlinetools
APKAN="$CMDLINE/cmdline-tools/latest/bin/apkanalyzer"
AAPT=$(ls "$CMDLINE"/build-tools/*/aapt2 | tail -1)
fail=0
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=1; }

echo "== 绝对检查（不依赖旧包）"
MANIFEST=$("$APKAN" manifest print "$NEW")
BADGING=$("$AAPT" dump badging "$NEW")

# 1. 高德 key。**这一条是这个脚本存在的理由。**
KEYVAL=$(printf '%s\n' "$MANIFEST" | awk '/com\.amap\.api\.v2\.apikey/{f=1} f&&/android:value=/{print; exit}')
if [ -z "$KEYVAL" ]; then
  bad "manifest 里没有 com.amap.api.v2.apikey —— 定位会报错误码 7"
elif printf '%s' "$KEYVAL" | grep -c 'value=""' >/dev/null 2>&1 && [ "${KEYVAL#*value=\"\"}" != "$KEYVAL" ]; then
  bad "高德 key 是空的：$KEYVAL"
elif [ "${KEYVAL#*\$\{}" != "$KEYVAL" ]; then
  bad "高德 key 的占位符没被替换：$KEYVAL"
else
  # 只报长度与尾号：这个脚本的输出会贴进工单和会话记录，key 不该跟着走
  k=$(printf '%s' "$KEYVAL" | sed 's/.*android:value="\([^"]*\)".*/\1/')
  ok "高德 key 已注入（${#k} 位，尾号 ${k: -4}）"
fi

# 2. 应用名。重解压后会退回 SDK 自带的名字，而界面上看不出来
label=$(printf '%s\n' "$BADGING" | awk -F"'" '/^application-label:/{print $2; exit}')
if [ "$label" = "虹选商家" ]; then ok "应用名：$label"; else bad "应用名是「$label」，应为「虹选商家」"; fi

# 3. 版本号
printf '%s\n' "$BADGING" | awk '/^package:/{print "  · " $0; exit}'

if [ -z "$OLD" ]; then
  echo
  if [ $fail = 0 ]; then echo "绝对检查通过（没给旧包，未做对比）"; else echo "有失败项 —— 别发这个包"; fi
  exit $fail
fi

echo "== 与旧包对比：$(basename "$OLD")"

# 4. 原生库。整族丢失一定看得出
if diff -q <(unzip -l "$OLD" | awk '/\.so$/{print $4}' | sort) \
           <(unzip -l "$NEW" | awk '/\.so$/{print $4}' | sort) >/dev/null; then
  ok ".so 清单一致"
else
  bad ".so 清单变了："
  diff <(unzip -l "$OLD" | awk '/\.so$/{print $4}' | sort) \
       <(unzip -l "$NEW" | awk '/\.so$/{print $4}' | sort) | head -6 || true
fi

# 5. 图标资源
if diff -q <(unzip -l "$OLD" | awk '/res\/.*icon.*\.(png|webp|xml)$/{print $4}' | sort) \
           <(unzip -l "$NEW" | awk '/res\/.*icon.*\.(png|webp|xml)$/{print $4}' | sort) >/dev/null; then
  ok "图标资源一致"
else
  bad "图标资源变了"
fi

# 6. dex 里的四个关键 SDK。**数类，不数文件** —— aar 在不在只有 dex 看得出
DEX_OLD=$("$APKAN" dex packages "$OLD" 2>/dev/null || true)
DEX_NEW=$("$APKAN" dex packages "$NEW" 2>/dev/null || true)
for pkg in com.igexin com.tencent.mm.opensdk com.amap.api io.dcloud; do
  a=$(printf '%s\n' "$DEX_OLD" | grep -c "$pkg" || true)
  b=$(printf '%s\n' "$DEX_NEW" | grep -c "$pkg" || true)
  if [ "$a" = "$b" ]; then ok "$pkg：$b 项（与旧包同）"; else bad "$pkg：旧 $a → 新 $b"; fi
done

echo
if [ $fail = 0 ]; then echo "全部通过"; else echo "有失败项 —— 别发这个包"; fi
exit $fail
