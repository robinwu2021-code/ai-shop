#!/usr/bin/env bash
# 发一版商家端 APK。**三步是连在一起的，分开做必漏一步。**
#
# 2026-08-28 就漏了：包打好了、装到测试机了，而官网静静地指着 8-20 的 0.1.0
# （5.7MB，真包 54MB）。八天里从官网下载的商家拿到的都是旧包，且没有任何报错 ——
# 官网那一行不会因为你打了新包就自己变。
#
# 用法：
#   scripts/release-bapp-apk.sh ~/Downloads/虹选商家-0.4.32-159.apk
#
# 它做六件事：验包 → 传 COS → 传服务器 /dl/ → 重指 latest 软链 → 写 latest.json → 改 site.config。
# **不打包**：离线打包工程在仓库外（见 memory / 《App签名与打包参数》），
# 各机路径不同，硬写进来只会在别人机器上假失败。
set -euo pipefail

APK="${1:-}"
[ -n "$APK" ] && [ -f "$APK" ] || { echo "用法：$0 <apk 路径>"; exit 2; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUCKET=hxmall-download-1301656997
REGION=ap-guangzhou
SSH_HOST=soukmind-tx-root
ENV_FILE="${TENCENT_ENV:-$HOME/work/env/tencent/tencent.env}"

AAPT="$(ls /opt/homebrew/share/android-commandlinetools/build-tools/*/aapt2 2>/dev/null | tail -1 || true)"
[ -n "$AAPT" ] || { echo "✗ 找不到 aapt2 —— 验不了包就不该发"; exit 1; }

# ── 1. 验包 ────────────────────────────────────────────────────────────
# 这几条都是踩过的：重解压 www 会静默清掉应用名与图标；
# 两处 versionCode 只抬一处，装上去版本号是新的而代码还是旧的。
BADGING="$($AAPT dump badging "$APK")"
PKG=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING")
VCODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -1)
VNAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)
LABEL=$(sed -n "s/^application-label:'\(.*\)'/\1/p" <<<"$BADGING" | head -1)
ICON=$(grep -c "^application-icon-" <<<"$BADGING" || true)

[ "$PKG" = "top.hxmall.bapp" ] || { echo "✗ 包名是 $PKG，不是 top.hxmall.bapp（拿成 android-shell 预览壳了？）"; exit 1; }
[ -n "$LABEL" ] || { echo "✗ 应用名是空的 —— 重解压 www 时被清掉了"; exit 1; }
[ "$ICON" -gt 0 ] || { echo "✗ 没有图标 —— 同上"; exit 1; }

# www 里的 versionCode 必须与 APK manifest 一致：
# 不一致时装是装得上、版本号也是新的，而 DCloud 运行时沿用手机上已解压的旧 www，
# **新代码静默不生效**。这是最难发现的一种坏包。
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$APK" "assets/apps/*/www/manifest.json" -d "$TMP" 2>/dev/null || true
WWW_MF=$(find "$TMP" -name manifest.json | head -1)
[ -n "$WWW_MF" ] || { echo "✗ 包里没有 www/manifest.json —— 这不是离线打包的产物"; exit 1; }
WWW_CODE=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['version']['code'])" "$WWW_MF")
[ "$WWW_CODE" = "$VCODE" ] || {
  echo "✗ www 里的 versionCode=$WWW_CODE 与 APK 的 $VCODE 不一致。"
  echo "  两处都要抬：b-app/src/manifest.json 与离线工程的 build.gradle。"
  echo "  不一致的后果是**装上去新代码静默不生效**，且版本号显示的是新的。"
  exit 1
}

MD5=$(md5 -q "$APK" 2>/dev/null || md5sum "$APK" | cut -d' ' -f1)
SIZE=$(wc -c < "$APK" | tr -d ' ')
echo "✓ 验包：$LABEL  $PKG  $VNAME (versionCode $VCODE)  ${SIZE} 字节  md5=$MD5"

# ── 2. COS：版本存档 + 稳定键 ──────────────────────────────────────────
# 上传不受「COS 默认域名禁止分发 APK」的限制，挡的只有公网下载（见 deploy/tencent/README.md）。
# 传它是为了异地存档，以及备案下来后官网换一行直链就能切。
set -a; . "$ENV_FILE"; set +a
for KEY in "b-app/hxmall-merchant-$VNAME-$VCODE.apk" "latest.apk"; do
  OUT=$(python3 "$ROOT/deploy/tencent/cos-put.py" "$BUCKET" "$REGION" "$KEY" "$APK")
  echo "$OUT" | sed 's/^/  /'
  grep -q "$MD5" <<<"$OUT" || { echo "✗ COS 的 ETag 与本地 md5 对不上：$KEY"; exit 1; }
done

# ── 3. 服务器直出（官网今天真正指向的地方）────────────────────────────
REMOTE="hxmall-merchant-$VNAME.apk"
scp -q "$APK" "$SSH_HOST:/data/app/ai-shop/web/dl/$REMOTE"
R_MD5=$(ssh "$SSH_HOST" "md5sum /data/app/ai-shop/web/dl/$REMOTE | cut -d' ' -f1")
[ "$R_MD5" = "$MD5" ] || { echo "✗ 服务器上的 md5 对不上：$R_MD5"; exit 1; }
echo "✓ 已传服务器：/dl/$REMOTE"

# ── 3.5 不带版本号的稳定地址 ──────────────────────────────────────────
#
# **小程序那条路不读官网的 site.config，读的是后端配置**
# （`SHOP_MERCHANT_APP_ANDROID` → `/mp/config/bootstrap` 的 merchantApp.android，
# C 端「复制 App 下载地址」用它）。那个值写在服务器 env 里，改它要重启服务 ——
# 于是它从 2026-08 起一直停在 **0.4.98**，而官网已经发到 0.5.21。
# 店主从小程序复制地址，下到的是二十多个版本前的包，**200、下得动、没有任何报错**。
#
# 解法是让那个值不再嵌版本号：env 指 latest 这个软链，脚本每次发版重指它。
# 这样后端配置一次配好，以后发版不用再动 env、不用重启。
ssh "$SSH_HOST" "sudo ln -sfn '$REMOTE' /data/app/ai-shop/web/dl/hxmall-merchant-latest.apk"
L_MD5=$(ssh "$SSH_HOST" "md5sum /data/app/ai-shop/web/dl/hxmall-merchant-latest.apk | cut -d' ' -f1")
[ "$L_MD5" = "$MD5" ] || { echo "✗ latest 软链取到的不是这一版：$L_MD5"; exit 1; }
echo "✓ latest 软链 → $REMOTE（md5 回读一致）"

# ── 3.6 版本清单 latest.json：**让「最新版是哪个」变成可查的，而不是抄来抄去** ──
#
# 在这之前，版本号写死在三处：官网 site.config、服务器 env、以及人的记性。
# 每处都要手工跟，于是每处都会掉队 —— env 那处掉了二十多个版本（0.4.98 vs 0.5.21），
# 而且掉队时**下载照样 200、照样装得上**，只是功能旧，没有任何信号。
#
# 现在真源只有这一份：发版写它，后端与官网都读它。发版即生效，
# 不用改代码、不用改配置、不用重启、不用重新部署官网。
#
# **清单里的 url 指带版本号的那个文件，不指 latest 软链。**
# 软链名字固定而内容会变，浏览器与 CDN 会把旧包缓存着当新包给出去
# （site 的 constraints.test.ts 早就写着这一条，我第一版正好踩中）。
# 动态由清单负责，防缓存由文件名负责 —— 两件事分开。
# latest 软链仍然留着：给读不到清单的那条兜底路径用。
RELEASED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
SIZE=$(wc -c < "$APK" | tr -d ' ')
ssh "$SSH_HOST" "sudo tee /data/app/ai-shop/web/dl/latest.json >/dev/null" <<JSON
{
  "version": "$VNAME",
  "versionCode": $VCODE,
  "url": "https://www.hxmall.top/dl/$REMOTE",
  "file": "$REMOTE",
  "size": $SIZE,
  "md5": "$MD5",
  "releasedAt": "$RELEASED_AT"
}
JSON
# 回读：写进去了不等于取得到（nginx 的 alias、权限、缓存都可能拦在中间）
J_VER=$(ssh "$SSH_HOST" "curl -sk --resolve www.hxmall.top:443:127.0.0.1 https://www.hxmall.top/dl/latest.json | sed -n 's/.*\"version\": \"\([^\"]*\)\".*/\1/p'")
[ "$J_VER" = "$VNAME" ] || { echo "✗ latest.json 取回来的版本是「$J_VER」，不是 $VNAME"; exit 1; }
echo "✓ latest.json → $VNAME（$VCODE），公网取回核对一致"

# ── 4. 官网那一行 ─────────────────────────────────────────────────────
# **这一步是这个脚本存在的理由。** 前三步不做也看得出来，这一步漏了看不出来。
python3 - "$ROOT/site/lib/site.config.ts" "$REMOTE" "$VNAME" <<'PY'
import re, sys
p, remote, vname = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
# **判「有没有匹配到」，不是判「内容有没有变」** —— 重跑同一个版本时内容本来就不该变，
# 拿 s2 == s 当失败会让幂等重跑报假错（第一版就是这么写的，当场撞上）。
s2, n1 = re.subn(r'(merchantAndroid:\s*)"[^"]*"', r'\1"/dl/%s"' % remote, s, count=1)
s2, n2 = re.subn(r'(merchantAndroidVersion:\s*)"[^"]*"', r'\1"%s"' % vname, s2, count=1)
if not (n1 and n2):
    print("✗ site.config.ts 里找不到 merchantAndroid / merchantAndroidVersion —— 字段名变了？")
    sys.exit(1)
open(p, "w", encoding="utf-8").write(s2)
print("✓ site.config.ts → /dl/%s（%s）%s" % (remote, vname, "" if s2 != s else "（本来就是这个值）"))
PY

cat <<TXT

还差最后一步（要部署官网才生效）：
  git add site/lib/site.config.ts && git commit
  然后按 deploy/tencent/README.md 发官网（rsync 干净 worktree → 服务器 npm run build -w site → 发布）

发完回读一次，别只看部署成功：
  curl -sk --resolve www.hxmall.top:443:127.0.0.1 https://www.hxmall.top/download/ | grep -o 'hxmall-merchant-[0-9.]*\.apk'
TXT
