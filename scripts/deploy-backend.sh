#!/usr/bin/env bash
#
# 后端上线。**把今天踩出来的几条规矩固定下来**，而不是每次凭记性 ssh 敲一遍。
#
# 用法：
#   scripts/deploy-backend.sh            # 从当前 HEAD 打包并上线
#   HOST=soukmind-tx scripts/deploy-backend.sh
#   DRY=1 scripts/deploy-backend.sh      # 只打包与核对，不切软链、不重启
#
# 这个脚本**不跑测试闸门** —— 那是 scripts/check-head-compiles.sh 的事，
# 它慢（约 4 分钟），而部署有时是在闸门刚绿之后立刻做的。
# 但它会把 HEAD 的提交号打出来，方便你对上刚才那次闸门跑的是不是同一个。
set -euo pipefail

HOST="${HOST:-soukmind-tx}"
REMOTE_DIR="${REMOTE_DIR:-/opt/ai-shop}"
LINK="$REMOTE_DIR/shop-app.jar"
SERVICE="${SERVICE:-ai-shop}"
HEALTH="${HEALTH:-http://localhost:8081/actuator/health}"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

say()  { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

# ── ⓪ JDK 21 ────────────────────────────────────────────────────────────
#
# 父 POM 的 enforcer 卡死 JDK 21，而这台机器的默认 java 不是它。
# **这个脚本第一次跑就死在这儿** —— 因为写它的人（我）整晚都是每条命令手动
# export，于是"它当然是配好的"。这正是脚本要消灭的那类假设，所以显式检查、
# 自己找，而不是让别人去猜那句 enforcer 报错是什么意思。
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
    for c in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
             /usr/lib/jvm/java-21-openjdk-amd64; do
        [ -x "$c/bin/java" ] && export JAVA_HOME="$c" && break
    done
fi
[ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21' \
    || die "找不到 JDK 21（父 POM 的 enforcer 要求它）。装好后 export JAVA_HOME 指过去再跑。"

HEAD_SHA="$(git rev-parse --short HEAD)"
HEAD_MSG="$(git log -1 --format=%s | cut -c1-60)"
say "本次要上线的是 HEAD = $HEAD_SHA  $HEAD_MSG"

# ── ① 记下出发时线上是什么 ────────────────────────────────────────────────
#
# **并行部署会互相无声覆盖。** 2026-08-29 07:55 与 07:57，两个会话在两分钟内
# 先后切了同一个软链，先推的那一版就这么没了 —— `ln -sfn` 是无条件覆盖，
# 双方都不会收到任何提示，而各自都验过「我的包装上了」。
#
# 打包要几分钟，正是别人也可能在推的窗口。所以出发时记一次、切换前再看一次，
# 变了就停下来问人，而不是覆盖掉。
BEFORE="$(ssh -o ConnectTimeout=10 "$HOST" "readlink -f '$LINK' 2>/dev/null || echo none")"
say "出发时线上：$(basename "$BEFORE")"

# ── ② 从干净 HEAD 副本构建 ────────────────────────────────────────────────
#
# **不在主工作区打包。** 这个目录常有多个会话同时在改，主工作区里别人未提交的
# 半成品会被一起烘进 jar 推上线，而 git status 里那些行看起来跟你毫无关系。
WT="$(mktemp -d)/deploy-head"
cleanup() { git worktree remove --force "$WT" >/dev/null 2>&1 || true; }
trap cleanup EXIT
git worktree add -q --detach "$WT" HEAD

TS="$(date +%Y%m%d-%H%M)"
JAR_NAME="shop-app-$TS.jar"
say "构建中（干净副本，约 2 分钟）…"
( cd "$WT/backend" && mvn -o clean package -pl shop-app -am -DskipTests -q ) \
    || die "构建失败"
LOCAL_JAR="$WT/backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar"
[ -f "$LOCAL_JAR" ] || die "构建完了却找不到 jar：$LOCAL_JAR"
ok "构建完成 $(du -h "$LOCAL_JAR" | cut -f1)"

# ── ③ 传包并核对 ─────────────────────────────────────────────────────────
#
# **新文件名，不覆盖在跑的那个。** 直接 cp 到在跑的 jar 上会让 JVM 的接口挂起
# 且不打任何日志（[[jar-overwrite-hangs-jvm]]），而回滚也没了退路。
say "上传 $JAR_NAME"
scp -q "$LOCAL_JAR" "$HOST:/tmp/$JAR_NAME"
LOCAL_MD5="$(md5 -q "$LOCAL_JAR" 2>/dev/null || md5sum "$LOCAL_JAR" | cut -d' ' -f1)"
REMOTE_MD5="$(ssh "$HOST" "md5sum /tmp/$JAR_NAME | cut -d' ' -f1")"
[ "$LOCAL_MD5" = "$REMOTE_MD5" ] || die "MD5 不一致，传输出问题了（本地 $LOCAL_MD5 / 远端 $REMOTE_MD5）"
ok "MD5 一致 $LOCAL_MD5"

if [ "${DRY:-}" = "1" ]; then
    ok "DRY=1：到此为止，没有切软链、没有重启"
    exit 0
fi

# ── ④ 切换前再看一次线上 ──────────────────────────────────────────────────
NOW="$(ssh "$HOST" "readlink -f '$LINK' 2>/dev/null || echo none")"
if [ "$NOW" != "$BEFORE" ]; then
    die "线上在我打包这段时间里被别人换过了：
       出发时 $(basename "$BEFORE")
       现在   $(basename "$NOW")
    直接切过去会把对方那一版无声抹掉。**先去问是谁推的、要不要保留**，
    确认后重跑本脚本（那时出发点就对上了）。"
fi
ok "线上仍是出发时那一版，可以切"

ssh "$HOST" "sudo install -o root -g root -m 644 /tmp/$JAR_NAME '$REMOTE_DIR/$JAR_NAME' \
    && sudo ln -sfn '$JAR_NAME' '$LINK' \
    && rm -f /tmp/$JAR_NAME"
ok "软链已指向 $JAR_NAME"

# 留一行给下一个来部署的人看 —— 谁、什么时候、哪个提交
ssh "$HOST" "printf '%s  %s  %s  %s\n' \"\$(date '+%F %T')\" '$JAR_NAME' '$HEAD_SHA' \"\$(whoami)\" \
    | sudo tee -a '$REMOTE_DIR/deploy.log' >/dev/null" || true

# ── ⑤ 重启并**守到 health=200 才算完** ───────────────────────────────────
#
# 2026-08-28 出过一次事故：重启后 health 一直是 000，而我被新话题岔开就走了 ——
# 线上挂了六分钟没人知道，最后是同伴发现的。所以这一步不许提前返回。
say "重启 $SERVICE"
ssh "$HOST" "sudo systemctl restart '$SERVICE'"

if ssh "$HOST" "for i in \$(seq 1 60); do
        c=\$(curl -s -o /dev/null -w '%{http_code}' '$HEALTH');
        [ \"\$c\" = '200' ] && { echo \"health=200（约 \$((i*3)) 秒）\"; exit 0; };
        sleep 3;
    done; echo \"health=\$c\"; exit 1"; then
    ok "起来了"
else
    die "没等到 health=200。**别走开** —— 现在线上是挂的。
    看日志：ssh $HOST 'sudo journalctl -u $SERVICE -n 80 --no-pager'
    要回滚：ssh $HOST \"sudo ln -sfn $(basename "$BEFORE") '$LINK' && sudo systemctl restart '$SERVICE'\""
fi

printf '\n\033[32m上线完成\033[0m  %s  ←  %s %s\n' "$JAR_NAME" "$HEAD_SHA" "$HEAD_MSG"
printf '回滚：ssh %s "sudo ln -sfn %s %s && sudo systemctl restart %s"\n' \
    "$HOST" "$(basename "$BEFORE")" "$LINK" "$SERVICE"
