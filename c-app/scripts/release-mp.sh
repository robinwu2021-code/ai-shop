#!/usr/bin/env bash
# 小程序发版：构建 → 校验 → 传到服务器 → 上传开发版。一条命令。
#
#   npm run release:mp -- 0.1.1 "修了核销未到货的提示"
#
# **为什么绕服务器**：微信上传认出口 IP 白名单，而开发机是家宽动态 IP
# （两天内 116.169.81.101 → 116.169.0.196）。服务器 106.55.27.246 固定，加一次就行。
#
# 脚本**只传开发版**。设体验版 / 提审 / 发布是不可逆的对外动作，留给人在后台点。
set -euo pipefail

VERSION="${1:?用法: npm run release:mp -- <版本号> [备注]}"
DESC="${2:-自动发布 $VERSION}"

HOST=soukmind-tx
REMOTE=/opt/ai-shop/mp-upload
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$HERE/dist/build/mp-weixin"

# **AppID 只有一个真源：manifest.json。**
# 它此前在三个地方各写一份（manifest、服务器 upload.mjs、密钥文件名），
# 换小程序时漏改任何一处，产物就会被传进**另一个小程序**里 ——
# 而 miniprogram-ci 不会拦：appid 与密钥对得上就传，传完一切正常，
# 只是你在新小程序的版本管理里找不到它，去旧的那个才看得到。
APPID="$(grep -oE '"appid"[[:space:]]*:[[:space:]]*"wx[a-z0-9]+"' "$HERE/src/manifest.json" \
  | head -1 | grep -oE 'wx[a-z0-9]+')"
[ -n "$APPID" ] || { echo "✗ 从 src/manifest.json 里读不出 mp-weixin 的 appid"; exit 1; }
echo "▶ 目标小程序 AppID：$APPID"

echo "▶ 1/4 构建"
( cd "$HERE" && rm -rf dist/build/mp-weixin && npm run build:mp-weixin >/dev/null )
[ -f "$DIST/app.json" ] || { echo "✗ 产物没生成：$DIST"; exit 1; }

# **上传前必须查这一条**：小程序没有「同源」，本地回环地址被烧进包里的话，
# 真机上每个请求都打到手机自己身上 —— 一直转圈，没有任何报错，
# 而包已经传上去了。查一次的成本远低于发现它的成本。
echo "▶ 2/4 校验产物里的 API 地址"
if grep -rqa "127\.0\.0\.1\|localhost" "$DIST"; then
  echo "✗ 产物里有 127.0.0.1/localhost —— 检查 c-app/.env.production 的 VITE_API_BASE"; exit 1
fi
# **这一句只是报告，不许它决定成败。** 原先写死了找 hxmall 域名，
# 而 .env.production 后来换成了服务器 IP —— grep 找不到就返回 1，
# 在 set -e 下把整个发布掐掉，且它本身不打印任何东西：
# 屏幕上只剩上一行「校验 API 地址」，看起来像校验没通过。
# 排查方向被带到「产物里是不是真有 localhost」，而那一条明明是过的。
BASES="$(grep -rhoaE "https?://[a-zA-Z0-9._:-]+" "$DIST" \
  | grep -vE "w3\.org|weixin\.qq\.com|qq\.com|schema" | sort -u || true)"
if [ -n "$BASES" ]; then
  echo "$BASES" | sed 's/^/  · /'
else
  echo "  · 产物里没有任何外部地址（同源部署时正常）"
fi

# **真机的硬门槛：HTTPS + 已备案域名。**
# HTTP 或裸 IP 在模拟器里一切正常（关掉 urlCheck 就行），到真机上是
# **每一个请求都失败**：首页空白、自提点「加载不出来」，而包已经传上去了。
# 那个开关恰好盖住了真机上唯一致命的问题，所以这里必须自己喊一声。
# 只警告不拦截 —— 用 IP 打包给「开了调试的手机」做内部测试是正当用法。
if echo "$BASES" | grep -qE "^  *· *http://|https?://[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"; then
  echo ""
  echo "  ⚠ API 地址不是 HTTPS 域名。真机上**所有请求都会被拒**（首页空白、"
  echo "    自提点加载不出来），除非在手机上「⋯ → 打开调试」跳过域名校验。"
  echo "    要发体验版给普通用户，得先备案通过、后台配好服务器域名，"
  echo "    再把 c-app/.env.production 的 VITE_API_BASE 换成 https 域名。"
  echo ""
fi

echo "▶ 3/4 传到 $HOST"
# COPYFILE_DISABLE=1 不能省：macOS 的 tar 会带出 ._* 资源叉文件，
# 小程序编译器读到就报 `/._app.wxss:1:1: Unknown word` —— 看着像样式写错了
COPYFILE_DISABLE=1 tar czf /tmp/mp-weixin.tgz -C "$HERE/dist/build" mp-weixin 2>/dev/null
scp -q /tmp/mp-weixin.tgz "$HOST:$REMOTE/mp-weixin.tgz"

echo "▶ 4/4 上传（版本 $VERSION）"
# **退出码要穿过来**：`node ... | grep` 的退出码是 grep 的，上传失败照样是 0，
# 于是下面那句「传完了」会在失败时照常打印 —— 这正是本脚本第 2 步要防的那种错，
# 别在自己身上再犯一遍。落盘再过滤，rc 单独取。
if ! ssh "$HOST" "cd $REMOTE && rm -rf mp-weixin && tar xzf mp-weixin.tgz 2>/dev/null; \
  MP_APPID='$APPID' MP_ROBOT='${MP_ROBOT:-1}' node upload.mjs '$VERSION' '$DESC' > /tmp/mp-up.log 2>&1; rc=\$?; \
  grep -aE '^\[upload\]|invalid ip' /tmp/mp-up.log; exit \$rc"; then
  echo
  echo "✗ 上传失败，代码没有传上去（完整日志在 $HOST:/tmp/mp-up.log）"
  exit 1
fi

echo
echo "传完了。后台还要点三下（脚本做不了，微信没开放自有小程序的这几个接口）："
echo "  1. 版本管理 → 把开发版设为体验版"
echo "  2. 提交审核"
echo "  3. 审核通过后发布"
