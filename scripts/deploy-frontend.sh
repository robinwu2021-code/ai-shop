#!/usr/bin/env bash
#
# 前端上线（ops-web / c-app / b-app）。与 scripts/deploy-backend.sh 同一套规矩：
# 锁、干净 HEAD 副本、可读的版本标识、部署后校验、备份可回滚。
#
# 用法：
#   scripts/deploy-frontend.sh ops-web
#   scripts/deploy-frontend.sh c-app
#   scripts/deploy-frontend.sh b-app
#   DRY=1 scripts/deploy-frontend.sh ops-web   # 只构建与自检，不上传
#
# **为什么要有它**：此前前端是手工 rsync，于是
#   · 在主工作区构建 —— 别人未提交的页面会被一起推上线；
#   · 产物里没有任何版本痕迹 —— 「ops-web 落后几个提交」只能靠 mtime 加
#     git log 反推，而那不可靠（2026-08-28 就是这么判断的）。
set -euo pipefail

APP="${1:-}"
case "$APP" in
    ops-web) BUILD_CMD='npm run build:prod'; OUT='out';           URL_PATH='/ops-web/' ;;
    c-app)   BUILD_CMD='npm run build:h5';   OUT='dist/build/h5'; URL_PATH='/c/' ;;
    b-app)   BUILD_CMD='H5_BASE=/b/ npm run build:h5'; OUT='dist/build/h5'; URL_PATH='/b/' ;;
    # 官网。**它此前只能手工发**，而手工发漏掉的恰恰是最后一步：
    # 2026-08-28 包打好、装到测试机了，官网静静指着八天前的 0.1.0（5.7MB，真包 54MB），
    # 从官网下载的商家八天里拿到的都是旧包，零报错。今天 0.4.35 又停了一整天。
    # 构建要读 site/content/**.md 与 brand/logo/mark-red.svg，两者都在仓库里，
    # 所以 worktree 副本自带；少任一个是构建期直接报错，不会静默出一个缺内容的站。
    site)    BUILD_CMD='npm run build';         OUT='out';           URL_PATH='/' ;;
    *) echo "用法: $0 <ops-web|c-app|b-app|site>" >&2; exit 2 ;;
esac

HOST="${HOST:-soukmind-tx}"
WWW="${WWW:-/var/www/ai-shop}"
DEST="$WWW/$APP"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

say() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

HEAD_SHA="$(git rev-parse --short HEAD)"
say "$APP ← HEAD $HEAD_SHA  $(git log -1 --format=%s | cut -c1-46)"

# ── 锁（每个前端一把，互不阻塞）────────────────────────────────────────────
LOCKDIR="$WWW/.deploy-$APP.lock"
if ! ssh "$HOST" "mkdir '$LOCKDIR' 2>/dev/null"; then
    HOLDER="$(ssh "$HOST" "cat '$LOCKDIR/owner' 2>/dev/null" || true)"
    die "有人正在部署 $APP：${HOLDER:-（未知）}
    确认那个进程已死之后：ssh $HOST \"rm -rf '$LOCKDIR'\""
fi
ssh "$HOST" "echo '$(whoami)@$(hostname -s) $(date '+%F %T')' > '$LOCKDIR/owner'" || true
WT=""
cleanup() {
    [ -n "$WT" ] && git worktree remove --force "$WT" >/dev/null 2>&1 || true
    ssh "$HOST" "rm -rf '$LOCKDIR'" >/dev/null 2>&1 || true
}
trap cleanup EXIT
ok "已拿到 $APP 的部署锁"

# ── 从干净 HEAD 副本构建 ──────────────────────────────────────────────────
#
# **前端在 worktree 里构建有两个坑**（都踩过，见 [[worktree-cannot-verify-frontend]]）：
#   ① node_modules **软链不行，必须拷** —— Turbopack 直接失败：
#      "Symlink [project]/ops-web/node_modules is invalid, it points out of
#       the filesystem root"。用 cp -Rc（APFS clonefile）秒级、几乎不占盘。
#   ② Next 还要把主目录的 .next 一起拷过去 —— next/font/google 构建时要拉字体，
#      副本里没缓存、本机又出不去，报的是一串指向 ibm_plex_sans_*.module.css 的
#      module-not-found，看着像依赖缺失，其实是拉不到字体。
WT="$(mktemp -d)/fe-head"
git worktree add -q --detach "$WT" HEAD
say "准备依赖（拷不软链）…"
cp -Rc "$ROOT/node_modules" "$WT/node_modules" 2>/dev/null || cp -R "$ROOT/node_modules" "$WT/node_modules"
[ -d "$ROOT/$APP/node_modules" ] && { cp -Rc "$ROOT/$APP/node_modules" "$WT/$APP/node_modules" 2>/dev/null \
    || cp -R "$ROOT/$APP/node_modules" "$WT/$APP/node_modules"; }
[ "$APP" = "ops-web" ] && [ -d "$ROOT/ops-web/.next" ] && { cp -Rc "$ROOT/ops-web/.next" "$WT/ops-web/.next" 2>/dev/null \
    || cp -R "$ROOT/ops-web/.next" "$WT/ops-web/.next"; }

say "构建 $APP …"
( cd "$WT/$APP" && eval "$BUILD_CMD" ) >/tmp/fe-build-$APP.log 2>&1 \
    || die "构建失败，日志：/tmp/fe-build-$APP.log（尾部）
$(tail -n 12 /tmp/fe-build-$APP.log)"
[ -d "$WT/$APP/$OUT" ] || die "构建完了却没有产物目录：$WT/$APP/$OUT"
ok "构建完成 $(du -sh "$WT/$APP/$OUT" | cut -f1)"

# ── 版本标识 ─────────────────────────────────────────────────────────────
#
# 产物里此前**没有任何东西**能说明它是哪个提交构建的。ops-web 有个 Next 的
# buildId，但那是内容哈希，对不回 git 提交。
printf 'sha=%s\nbuilt=%s\napp=%s\n' \
    "$HEAD_SHA" "$(date '+%F %T %z')" "$APP" > "$WT/$APP/$OUT/VERSION"

if [ "${DRY:-}" = "1" ]; then
    ok "DRY=1：构建与 VERSION 就绪，未上传"
    cat "$WT/$APP/$OUT/VERSION" | sed 's/^/    /'
    exit 0
fi

# ── 上传（先备份）────────────────────────────────────────────────────────
TS="$(date +%Y%m%d-%H%M)"
say "备份现网并上传"
#
# **属主用数字 uid:gid，不用名字。** 第一版写的是 `stat -c '%U:%G'` ——
# 这些目录的属主是 uid 501（本机的用户），服务器上没有这个账号，
# %U 于是返回 `UNKNOWN`，chown 直接失败。
#
# 而失败的位置最坏：rsync 已经落地、校验和 deploy.log 都还没跑，
# 于是**部署其实成功了，脚本却报失败** —— 照着去回滚会把好版本退掉。
# 所以属主先取好再动，且用数字。
OWNER="$(ssh "$HOST" "stat -c '%u:%g' '$DEST' 2>/dev/null || echo ''")"
ssh "$HOST" "sudo cp -a '$DEST' '$DEST.bak-$TS'" || die "备份失败，没敢往下走"
rsync -a --delete -e ssh "$WT/$APP/$OUT/" "$HOST:/tmp/fe-$APP-new/"
ssh "$HOST" "sudo rsync -a --delete /tmp/fe-$APP-new/ '$DEST/' && rm -rf /tmp/fe-$APP-new" \
    || die "上传失败。现网可能是半更新状态，回滚：
    ssh $HOST \"sudo rsync -a --delete '$DEST.bak-$TS/' '$DEST/'\""
[ -n "$OWNER" ] && ssh "$HOST" "sudo chown -R '$OWNER' '$DEST'" \
    || say "（没取到原属主，保持 rsync 落地时的属主 —— nginx 只要读得到就行）"
ok "已上传（备份 $(basename "$DEST.bak-$TS")）"

# ── 校验：比对**内容**，不是状态码 ───────────────────────────────────────
#
# nginx 那几条 location 都带 `try_files ... /index.html` —— VERSION 不存在时
# 会回落到首页并返回 **200**。只看状态码是假绿，必须把 sha 读出来对。
#
# ⚠️ **要带真实 Host 且跟随跳转**（-L）。
# 用 `Host: localhost` 请求时 nginx 认不出这个站，回 **301** 跳到正式域名，
# 而 curl 不跟随的话读到的是那段 301 的 HTML ——
# 于是<b>部署明明成功，校验却报「读出来的不是这一版」</b>。
# 2026-09-02 撞过一次：文件已经上去了、内容也对，只有校验在报错。
# 这类「校验本身错了」的失败最费时间：它把人引去查部署，而部署是好的。
LIVE="$(ssh "$HOST" "curl -sL -H 'Host: www.hxmall.top' 'http://127.0.0.1${URL_PATH}VERSION'" || true)"
LIVE_SHA="$(printf '%s' "$LIVE" | sed -n 's/^sha=//p')"
if [ "$LIVE_SHA" = "$HEAD_SHA" ]; then
    ok "线上 ${URL_PATH}VERSION 读出 sha=$LIVE_SHA"
else
    die "上传了，但 ${URL_PATH}VERSION 读出来的不是这一版：
       期望 $HEAD_SHA
       实际 ${LIVE_SHA:-（读到的不是 VERSION —— 多半被 try_files 回落到了 index.html）}
    回滚：ssh $HOST \"sudo rsync -a --delete '$DEST.bak-$TS/' '$DEST/'\""
fi

ssh "$HOST" "printf '%s  %s  %s  %s\n' \"\$(date '+%F %T')\" '$APP' '$HEAD_SHA' \"\$(whoami)\" \
    | sudo tee -a '$WWW/deploy.log' >/dev/null" || true

# ── 备份保留 ─────────────────────────────────────────────────────────────
#
# 每次部署留一份，没人回头删 —— 2026-08-29 一查已经堆了 17 个。
# 每个 app 留最近 3 份：够覆盖「上一版 / 上上版」这两种现实的回滚需求，
# 再往前的版本 git 里有，重新构建比留着更可靠（那些目录不带任何版本标识，
# 光看名字分不清里面是哪个提交 —— 这正是 VERSION 文件要解决的问题，
# 而旧备份里没有它）。
ssh "$HOST" "cd '$WWW' 2>/dev/null || exit 0
    ls -1dt '$APP'.bak-* 2>/dev/null | tail -n +4 | while read -r d; do
        sudo rm -rf -- \"\$d\"
    done" >/dev/null 2>&1 || true

printf '\n\033[32m%s 上线完成\033[0m  %s\n' "$APP" "$HEAD_SHA"
printf '回滚：ssh %s "sudo rsync -a --delete %s.bak-%s/ %s/"\n' "$HOST" "$DEST" "$TS" "$DEST"
