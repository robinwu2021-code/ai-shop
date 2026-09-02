#!/usr/bin/env bash
#
# B 端 APK 离线打包 —— 一条命令走完手册的六步。
#   docs/technical/design/App离线打包-操作手册.md
#
# 用法：
#   b-app/offline/build-apk.sh                      # 打包 + 体检
#   b-app/offline/build-apk.sh --install e7d0764c   # 再装到指定设备并自检
#   b-app/offline/build-apk.sh --no-build           # www 已经是最新的，跳过 npm run build:app
#   OFFLINE_PROJECT=<路径> b-app/offline/build-apk.sh   # 换机器时指工程
#
# ─────────────────────────────────────────────────────────────────────────
# **这个脚本存在的理由：认工程。**
#
# 仓库里已经有三道守卫，而且都挺好：
#   · amap-key.gradle        构建期：高德 key 读得到、manifest 两条声明在
#   · version-alignment.gradle 构建期：两处 versionCode 一致
#   · verify-apk.sh          事后：产物对着旧包逐项体检
#
# 但前两道**是靠离线工程 build.gradle 末尾那两行 `apply from:` 接进来的** ——
# 也就是说：**用错工程时，这两道闸门根本不存在**，不是失败，是没跑。
#
# 2026-08-30 我就是这么打出一个坏包的：凭记忆里「工程被清掉就重新解压」那句话，
# 把 SDK 压缩包解压到 /tmp 打了一份。那是厂商默认工程 —— 应用名
# `HBuilder-SimpleDemo-AS`、无高德 key、igexin/微信/高德/dcloud 四个库一个都没有。
# 产物 30M（真包 55M），gradle 一路 BUILD SUCCESSFUL，装上模拟器也能跑。
# 而已配好的工程一直好好地在 `~/Downloads/最新版/5.24/sdk/` 下 —— `sdk` 是目录，
# 与那个同名 .zip 并列，`ls` 到 zip 就以为工程没了。
#
# 「重新解压」只在工程**真的不见了**时才对。工程还在却重解压，等于把配置
# 全退回出厂值，而构建期没有任何一道检查会拦 —— 因为拦它的那两道正好也被覆盖了。
#
# 所以 §0 先认工程：路径在、应用名对、两行接线在、keystore 在。缺一样就停，
# **不自作主张去解压** —— 那正是上次出事的动作。
# ─────────────────────────────────────────────────────────────────────────
#
# ⚠️ 全篇不用 `grep -q` / `head -1` 接在长命令后面：命中即关管道，上游吃到
# SIGPIPE，在 `set -o pipefail` 下整条脚本以 141 退出，而输出看起来像正常结束。
# 一律先存进变量再挑行 —— 这条是 verify-apk.sh 踩出来的，同样适用于这里。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

OFFLINE_PROJECT="${OFFLINE_PROJECT:-$HOME/Downloads/最新版/5.24/sdk/Android-SDK@5.24.82669_20260813/HBuilder-Integrate-AS}"
APPID="__UNI__59E912D"
APP_LABEL="虹选商家"
PKG="top.hxmall.bapp"
KEYSTORE="$HOME/keys/hxmall-release.jks"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
OUT_DIR="${OUT_DIR:-$HOME/Downloads}"

DO_BUILD=1
INSTALL_TO=""
ALLOW_EMULATOR=0
while [ $# -gt 0 ]; do
    case "$1" in
        --no-build)        DO_BUILD=0; shift ;;
        --install)         INSTALL_TO="${2:?--install 要跟设备序列号}"; shift 2 ;;
        --allow-emulator)  ALLOW_EMULATOR=1; shift ;;
        -h|--help)         sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "不认识的参数：$1（--help 看用法）" >&2; exit 2 ;;
    esac
done

say()  { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

GRADLE_FILE="$OFFLINE_PROJECT/simpleDemo/build.gradle"
STRINGS_FILE="$OFFLINE_PROJECT/simpleDemo/src/main/res/values/strings.xml"
WWW="$OFFLINE_PROJECT/simpleDemo/src/main/assets/apps/$APPID/www"
APK_OUT="$OFFLINE_PROJECT/simpleDemo/build/outputs/apk/release/simpleDemo-release.apk"

# ── ⓪ 认工程 ────────────────────────────────────────────────────────────
say "⓪ 认工程"

[ -d "$OFFLINE_PROJECT" ] || die "离线工程不在：$OFFLINE_PROJECT
    **先确认它是不是真的不见了** —— 它在 .../5.24/sdk/ 下，与同名 .zip 并列，
    很容易 ls 到 zip 就以为没了。真的没了才解压那个 zip，
    解出来的是厂商默认工程，应用名/图标/高德 key/各 aar 都要重配一遍。"

[ -f "$GRADLE_FILE" ]  || die "找不到 $GRADLE_FILE"
[ -f "$STRINGS_FILE" ] || die "找不到 $STRINGS_FILE"

LABEL_LINE=$(awk '/name="app_name"/{print; exit}' "$STRINGS_FILE" || true)
case "$LABEL_LINE" in
    *"$APP_LABEL"*) ok "应用名：$APP_LABEL" ;;
    *) die "应用名不对，这多半是厂商默认工程，拒绝打包。
    读到的：${LABEL_LINE:-（没读到 app_name）}
    期望含：$APP_LABEL
    文件：  $STRINGS_FILE
    用默认工程打出来的包：名字叫 HBuilder-SimpleDemo-AS、图标是小机器人、
    没有高德 key、四个原生库一个都没有 —— 而 gradle 会一路 BUILD SUCCESSFUL。" ;;
esac

# 两行接线：仓库里的两道构建期闸门全靠它们才存在。
# 缺了不是「闸门失败」，是**闸门不存在** —— 这正是最难发现的一种。
APPLY_LINES=$(grep -c 'b-app/offline/\(amap-key\|version-alignment\)\.gradle' "$GRADLE_FILE" || true)
[ "${APPLY_LINES:-0}" -eq 2 ] || die "离线工程少了 apply from 接线（找到 ${APPLY_LINES:-0} 行，要 2 行）。
    把这两行加回 $GRADLE_FILE 末尾：
      apply from: \"$ROOT/b-app/offline/amap-key.gradle\"
      apply from: \"$ROOT/b-app/offline/version-alignment.gradle\"
    它们把「高德 key 注入」与「两处 versionCode 对齐」两道闸门接进构建。
    没有它们，构建照样成功，坏包要等到真机上点定位、点新页面才发现。"
ok "两行 apply from 接线在"

[ -f "$KEYSTORE" ] || die "签名 keystore 不在：$KEYSTORE"
ok "keystore 在"
[ -d "$JDK" ] || die "JDK 不在：$JDK（用 JAVA_HOME 指一个 17）"
ok "JDK：$JDK"

# ── ① 版本号：以仓库里那份为单一真源 ────────────────────────────────────
# 手册 §1 要求两处一起抬，而「要改两处」本身就是漏改的来源。
# 这里让 b-app/src/manifest.json（**在仓库里、可提交、可 review**）当唯一真源，
# 由脚本同步进仓库外的 gradle。人只改一处，另一处不可能落后。
say "① 版本号"
read -r VNAME VCODE <<<"$(python3 -c "
import json
m = json.load(open('b-app/src/manifest.json', encoding='utf-8'))
print(m['versionName'], m['versionCode'])
")"
[ -n "$VCODE" ] || die "b-app/src/manifest.json 里读不到 versionCode"
ok "真源 b-app/src/manifest.json：$VNAME / $VCODE"

GRADLE_CODE=$(awk '/versionCode /{print $2; exit}' "$GRADLE_FILE" || true)
if [ "$GRADLE_CODE" != "$VCODE" ]; then
    python3 - "$GRADLE_FILE" "$VNAME" "$VCODE" <<'PY'
import re, sys
p, vname, vcode = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding='utf-8').read()
s, n1 = re.subn(r'versionCode\s+\d+', f'versionCode {vcode}', s, count=1)
s, n2 = re.subn(r'versionName\s+"[^"]*"', f'versionName "{vname}"', s, count=1)
if not (n1 and n2):
    sys.exit(f"没能改动 build.gradle 的版本号（versionCode 改了 {n1} 处、versionName {n2} 处）")
open(p, 'w', encoding='utf-8').write(s)
PY
    ok "已把离线工程 gradle 同步到 $VNAME / $VCODE（原为 ${GRADLE_CODE:-?}）"
else
    ok "离线工程 gradle 已是 $VCODE"
fi

# 抬没抬版本是**人的决定**，脚本只提醒不代劳：装在手机上的那版若与本次同号，
# 覆盖安装后 DCloud 运行时会沿用已解压的旧 www —— 新代码静默不生效。
if [ -n "$INSTALL_TO" ]; then
    ON_DEVICE=$(adb -s "$INSTALL_TO" shell dumpsys package "$PKG" 2>/dev/null \
        | awk '/versionCode=/{print $1; exit}' | sed 's/.*versionCode=//' || true)
    if [ -n "$ON_DEVICE" ] && [ "$ON_DEVICE" = "$VCODE" ]; then
        printf '  \033[33m!\033[0m 设备上已装的就是 %s —— 同号覆盖安装会沿用旧 www，新代码可能静默不生效。\n' "$VCODE"
        printf '    要么先抬 b-app/src/manifest.json 的 versionCode，要么装前 adb uninstall %s\n' "$PKG"
    fi
fi

# ── ② 构建 App 资源 ─────────────────────────────────────────────────────
say "② 构建 App 资源"
if [ "$DO_BUILD" -eq 1 ]; then
    (cd b-app && npm run build:app >/dev/null 2>&1) || die "npm run build:app 失败（去掉重定向再跑一遍看报错）"
    ok "b-app/dist/build/app 已重建"
else
    ok "跳过构建（--no-build）"
fi

DIST="b-app/dist/build/app"
[ -d "$DIST" ] || die "产物不在：$DIST"

# API base：模拟器用的 10.0.2.2 打进包里，真机上每个列表都「网络异常」，
# 而模拟器上一切正常 —— 看不出问题的那一类错。手册 §2。
#
# **`https?` 不是可有可无的**：2026-09-01「备案下来，三端统一走域名」之后
# `.env.production` 是 `https://www.hxmall.top`，而这条正则只认 `http://` ——
# 于是它抓不到任何东西，落进下面那条「没找到 API base，产物可能不完整」，
# 把一个**完全正常的产物**判成坏包。上一个 APK 是 8-30 打的，所以这道闸
# 在改成 https 之后一次都没跑过，今天（09-02）第一次跑就拦住了自己人。
#
# 判据本身是对的（要拦本机地址），错的是它只认得半个取值域。
BASES=$(grep -oE 'const [A-Za-z_$]*="https?://[^"]*"' "$DIST/app-service.js" || true)
case "$BASES" in
    *10.0.2.2*|*127.0.0.1*|*localhost*)
        die "产物里的 API base 指向本机/模拟器，真机上一个接口都不通：
    $BASES
    改 b-app/.env.production，然后重跑（别加 --no-build）" ;;
    "") die "在 $DIST/app-service.js 里没找到 API base，产物可能不完整" ;;
    *)  ok "API base：$(printf '%s' "$BASES" | sed 's/.*="//; s/"$//' | paste -sd' ' -)" ;;
esac

DIST_CODE=$(python3 -c "import json;print(json.load(open('$DIST/manifest.json'))['version']['code'])")
[ "$DIST_CODE" = "$VCODE" ] || die "产物里的 version.code 是 $DIST_CODE，而真源是 $VCODE。
    多半是改了 manifest.json 却没重跑 build:app（别加 --no-build）。"
ok "产物 www 版本：$DIST_CODE"

# ── ③ 换 www ────────────────────────────────────────────────────────────
say "③ 换 www"
[ -d "$(dirname "$WWW")" ] || die "assets 下没有 apps/$APPID 目录：$(dirname "$WWW")"
rm -rf "$WWW.prev"
[ -d "$WWW" ] && cp -R "$WWW" "$WWW.prev"
rm -rf "$WWW"
cp -R "$DIST" "$WWW"
if [ -d "$WWW.prev" ]; then
    # 产物是压缩过的，按源码里的名字 grep 一定搜不到；与上一版比对才是可行的判据。
    if cmp -s "$WWW/app-service.js" "$WWW.prev/app-service.js"; then
        printf '  \033[33m!\033[0m app-service.js 与上一版逐字节相同 —— 这次没有页面代码改动进包\n'
    else
        ok "与上一版不同，改动进去了"
    fi
else
    ok "首次换装（无 .prev 可比）"
fi

# ── ④ 打包 ──────────────────────────────────────────────────────────────
say "④ 打包（release）"
rm -f "$APK_OUT"
if ! (cd "$OFFLINE_PROJECT" && JAVA_HOME="$JDK" ./gradlew :simpleDemo:assembleRelease >/tmp/apk-build.log 2>&1); then
    tail -30 /tmp/apk-build.log >&2
    die "gradle 失败（完整日志 /tmp/apk-build.log）"
fi
[ -f "$APK_OUT" ] || die "构建说成功，却找不到产物：$APK_OUT"
ok "$(du -h "$APK_OUT" | cut -f1)  $APK_OUT"

# ── ⑤ 产物体检 ──────────────────────────────────────────────────────────
# 手册 §5：这条链路上出过的每一次事故，构建都是成功的。
# 「只能靠人记得跑的守卫等于没有守卫」——所以这里无条件跑，不给跳过的开关。
say "⑤ 产物体检"
# 对照的必须是**上一个版本**的包。不排除同版本的话，重跑一次就会拿这次自己的
# 上一版产物当对照 —— 两边由同一份配置打出，四个库的计数当然一致，
# 而「配置被覆盖了」正是要靠这个对比发现的。自己对自己＝这项检查失效。
OLD=$(ls -t "$OUT_DIR"/${APP_LABEL}-*.apk 2>/dev/null \
        | grep -v "^${OUT_DIR}/${APP_LABEL}-${VNAME}-${VCODE}\.apk$" | awk 'NR==1' || true)
if [ -n "$OLD" ]; then
    say "对照旧包：$(basename "$OLD")"
    b-app/offline/verify-apk.sh "$APK_OUT" "$OLD" || die "体检没过，不发这个包"
else
    printf '  \033[33m!\033[0m %s 下没有旧包可对照，只做绝对检查\n' "$OUT_DIR"
    b-app/offline/verify-apk.sh "$APK_OUT" || die "体检没过，不发这个包"
fi

# ── ⑥ 命名归档 ──────────────────────────────────────────────────────────
DEST="$OUT_DIR/${APP_LABEL}-${VNAME}-${VCODE}.apk"
cp "$APK_OUT" "$DEST"
say "⑥ 产物"
ok "$DEST"

# ── ⑦ 装机自检（可选）───────────────────────────────────────────────────
if [ -n "$INSTALL_TO" ]; then
    say "⑦ 装机自检 → $INSTALL_TO"
    # 手册 §6：「认准真机的序列号，别装到模拟器上」。
    # 模拟器验不了原生能力（定位/扫码/支付），在那儿绿了会给出假的放行信号。
    case "$INSTALL_TO" in
        emulator-*)
            [ "$ALLOW_EMULATOR" -eq 1 ] || die "目标是模拟器。原生能力（定位/扫码/支付）在模拟器上验不了，
    在那儿绿了是假的放行信号。真要装加 --allow-emulator。" ;;
    esac

    adb -s "$INSTALL_TO" install -r "$DEST" >/dev/null || die "安装失败"
    INSTALLED=$(adb -s "$INSTALL_TO" shell dumpsys package "$PKG" 2>/dev/null \
        | awk '/versionCode=/{print $1; exit}' | sed 's/.*versionCode=//')
    [ "$INSTALLED" = "$VCODE" ] || die "装上的是 $INSTALLED，不是 $VCODE"
    ok "已安装 $VNAME / $VCODE"

    # 从手机那一侧探后端：本机 curl 验不了（本机 DNS 走内网代理，与手机不是一条路）
    BASE=$(printf '%s' "$BASES" | sed 's/.*="//; s/"$//' | awk 'NR==1')
    HEALTH=$(adb -s "$INSTALL_TO" shell "curl -s --max-time 12 $BASE/actuator/health" 2>/dev/null || true)
    case "$HEALTH" in
        *'"status":"UP"'*) ok "手机侧探后端：UP（$BASE）" ;;
        *) printf '  \033[33m!\033[0m 手机侧探 %s 没拿到 UP：%s\n' "$BASE" "${HEALTH:0:120}" ;;
    esac

    adb -s "$INSTALL_TO" logcat -c 2>/dev/null || true
    adb -s "$INSTALL_TO" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 10
    PID=$(adb -s "$INSTALL_TO" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
    [ -n "$PID" ] || die "启动后进程不在 —— 启动即崩。看 adb -s $INSTALL_TO logcat -d | grep -i fatal"
    ok "进程活着（pid $PID）"

    CRASH=$(adb -s "$INSTALL_TO" logcat -d -t 400 2>/dev/null | grep -iE "FATAL|AndroidRuntime" || true)
    [ -z "$CRASH" ] || die "logcat 里有崩溃：
$CRASH"
    ok "无 FATAL"

    printf '\n\033[32m装机自检通过\033[0m  界面要点的话得解锁手机，那一步交给人。\n'
fi

printf '\n\033[32m打包完成\033[0m  %s\n' "$DEST"
printf '收尾：b-app/src/manifest.json 的版本号记得提交（gradle 那处在仓库外，提交不了）。\n'
