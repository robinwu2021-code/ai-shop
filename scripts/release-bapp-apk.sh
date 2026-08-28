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
# 它做四件事：验包 → 传 COS（版本存档 + latest）→ 传服务器 /dl/ → 改 site.config。
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
scp -q "$APK" "$SSH_HOST:/var/www/ai-shop/dl/$REMOTE"
R_MD5=$(ssh "$SSH_HOST" "md5sum /var/www/ai-shop/dl/$REMOTE | cut -d' ' -f1")
[ "$R_MD5" = "$MD5" ] || { echo "✗ 服务器上的 md5 对不上：$R_MD5"; exit 1; }
echo "✓ 已传服务器：/dl/$REMOTE"

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
