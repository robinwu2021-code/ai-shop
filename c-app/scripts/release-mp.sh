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
grep -rhoa "https://[a-zA-Z0-9._-]*hxmall[a-zA-Z0-9._-]*" "$DIST" | sort -u | sed 's/^/  ✓ /'

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
  node upload.mjs '$VERSION' '$DESC' > /tmp/mp-up.log 2>&1; rc=\$?; \
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
