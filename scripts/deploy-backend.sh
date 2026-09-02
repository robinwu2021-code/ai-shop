#!/usr/bin/env bash
#
# 后端上线。**把今天踩出来的几条规矩固定下来**，而不是每次凭记性 ssh 敲一遍。
#
# 用法：
#   scripts/deploy-backend.sh            # 从当前 HEAD 打包并上线
#   HOST=soukmind-tx scripts/deploy-backend.sh
#   DRY=1 scripts/deploy-backend.sh      # 只打包与核对，不切软链、不重启
#   scripts/deploy-backend.sh --rollback # 切回上一版并守到 health=200
#
# 这个脚本**不跑测试闸门** —— 那是 scripts/check-head-compiles.sh 的事，
# 它慢（约 4 分钟），而部署有时是在闸门刚绿之后立刻做的。
# 但它会把 HEAD 的提交号打出来，方便你对上刚才那次闸门跑的是不是同一个。
set -euo pipefail

HOST="${HOST:-soukmind-tx}"

# ── 发哪个产物 ───────────────────────────────────────────────────────────
#
# 主应用与支付域是**两个进程**（2026-09-01 起），而这个脚本此前只认前者 ——
# 于是 pay-svc 只能手敲 ssh 发，那条路上没有锁、没有干净副本、没有 health 守候，
# 也没有回滚软链。**四样安全绳一样都没有的那条路，不该是常走的那条。**
#
#   scripts/deploy-backend.sh            # 主应用（默认，行为与此前逐字一致）
#   scripts/deploy-backend.sh pay-svc    # 支付域独立进程
APP="${1:-shop-app}"
case "$APP" in
    shop-app)
        MVN_MODULE="shop-app"; JAR_IN_REPO="shop-app/target/shop-app-0.1.0-SNAPSHOT.jar"
        REMOTE_DIR="${REMOTE_DIR:-/opt/ai-shop}"; LINK_NAME="shop-app.jar"
        SERVICE="${SERVICE:-ai-shop}"
        HEALTH="${HEALTH:-http://localhost:8081/actuator/health}" ;;
    pay-svc)
        MVN_MODULE="pay/pay-svc"; JAR_IN_REPO="pay/pay-svc/target/pay-svc-0.1.0-SNAPSHOT.jar"
        REMOTE_DIR="${REMOTE_DIR:-/opt/ai-shop-pay}"; LINK_NAME="pay-svc.jar"
        SERVICE="${SERVICE:-ai-shop-pay}"
        # pay-svc 没有 actuator（它只暴露 /internal 与 /callback）。
        # 拿 /internal 的 401 当活口：**401 说明容器起来了、过滤链在**，
        # 而进程没起是连不上（000）。这两者必须分得开 —— 见 wait_healthy 的注释。
        HEALTH="${HEALTH:-http://localhost:8083/internal/pay/fee-rules}"
        HEALTH_OK="${HEALTH_OK:-401}" ;;
    *) echo "不认识的产物：$APP（只支持 shop-app / pay-svc）" >&2; exit 2 ;;
esac
HEALTH_OK="${HEALTH_OK:-200}"
LINK="$REMOTE_DIR/$LINK_NAME"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

say()  { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

# **守到 health=200 才算完。** 2026-08-28 出过一次事故：重启后 health 一直是
# 000，而我被新话题岔开就走了 —— 线上挂了六分钟没人知道，最后是同伴发现的。
# 所以这一步不许提前返回，且部署与回滚两条路都走它。
wait_healthy() {
    ssh "$HOST" "for i in \$(seq 1 60); do
            c=\$(curl -s -o /dev/null -w '%{http_code}' '$HEALTH');
            [ \"\$c\" = '$HEALTH_OK' ] && { echo \"health=$HEALTH_OK（约 \$((i*3)) 秒）\"; exit 0; };
            sleep 3;
        done; echo \"health=\$c\"; exit 1"
}

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

# ── ⓪ 闸门验的那份，是不是就是要发的这份 ─────────────────────────────────
#
# **「验过的那份」与「发出去的那份」可以不是一份。**
# 闸门跑四到六分钟，而这个目录常有多个会话在推 —— 跑完之后 HEAD 会前进，
# 而这里建的是**当时的** HEAD。2026-08-29 真实发生过：闸门验 acf0679e，
# 部署建 ea4d428a，中间多了两笔。那次无害（多出来的 backend 改动只是一个基线
# 文本，不进 jar），但那是**人对了一眼**才知道的，而人对一眼是会累的。
#
# 同一天另一条会话踩的是这件事的**空间版本**：打 APK 时跑的构建命令读的是工作区
# 而不是 HEAD，于是装到测试机上的是「打包那一刻的混合体」。
# 时间上的漂与空间上的漂，同一句话：**验的那份 ≠ 发出去的那份。**
#
# 处理分两档，判据是「漂过来的提交有没有动 backend/」：
#   · 没动 → jar 的行为与闸门验过的一致，打印一行说明就走（今天那次就是这一档）
#   · 动了 → **拦下来**，列出是哪几笔，要显式 ALLOW_GATE_DRIFT=1 才继续
# 只打印不拦的话，它就是又一条「打了一路没人看见」的警告 —— 这个仓库今天刚清掉两条那种。
GATE_FILE="$(git rev-parse --git-dir)/gate-verified-sha"
if [ -f "$GATE_FILE" ]; then
    # 用 --short 归一化，**不要 cut -c1-7**：这个仓库的 --short 给的是 8 位，
    # 截 7 位的话两边永远不相等，「闸门验的就是这一版」那条分支从此不会触发 ——
    # 而它退化成的样子（落到漂移分支、恰好没有 backend 改动、打印一行「行为一致」）
    # 看起来完全正常。写这段的时候就踩了一次。
    GATE_SHA="$(git rev-parse --short "$(head -1 "$GATE_FILE")")"
    GATE_WHAT="$(sed -n '2p' "$GATE_FILE")"
    if [ "$GATE_SHA" = "$HEAD_SHA" ]; then
        ok "闸门验的就是这一版（$GATE_SHA）${GATE_WHAT:+ —— $GATE_WHAT}"
    else
        DRIFT="$(git log --oneline "$GATE_SHA..$HEAD_SHA" -- backend 2>/dev/null || true)"
        if [ -z "$DRIFT" ]; then
            say "闸门验的是 $GATE_SHA，本次构建 $HEAD_SHA —— 其间**没有 backend 改动**，jar 行为一致"
        elif [ "${ALLOW_GATE_DRIFT:-}" = "1" ]; then
            say "⚠ 闸门验的是 $GATE_SHA，而这几笔 backend 改动没被它验过（ALLOW_GATE_DRIFT=1 放行）："
            echo "$DRIFT" | sed 's/^/    /'
        else
            printf '%s\n' "$DRIFT" | sed 's/^/    /' >&2
            die "闸门验的是 $GATE_SHA，本次要建 $HEAD_SHA —— 上面这几笔 backend 改动**没被闸门验过**。
  两条路：重跑一次 scripts/check-head-compiles.sh（推荐），
  或者确认过它们无害之后 ALLOW_GATE_DRIFT=1 再跑本脚本。"
        fi
    fi
else
    say "没有闸门记录（$GATE_FILE 不存在）—— 跑过 check-head-compiles.sh 之后才会有"
fi

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

# ── 回滚 ─────────────────────────────────────────────────────────────────
#
# 做成一条命令，而不是出事时让人照着提示拼 ssh —— 人在那个时刻最不该做的
# 就是现场拼命令。目标取自服务器上的 deploy.log 倒数第二行。
if [ "${1:-}" = "--rollback" ]; then
    PREV="$(ssh "$HOST" "tail -n 2 '$REMOTE_DIR/deploy.log' 2>/dev/null | head -n 1 | awk '{print \$3}'")"
    [ -n "$PREV" ] || die "deploy.log 里没有上一版可回退（它是 2026-08-29 才开始记的）"
    ssh "$HOST" "test -f '$REMOTE_DIR/$PREV'" || die "上一版的包已经不在了：$PREV"
    say "回滚到 $PREV"
    ssh "$HOST" "sudo ln -sfn '$PREV' '$LINK' && sudo systemctl restart '$SERVICE'"
    wait_healthy || die "回滚后没等到 health=200 —— 现在线上是挂的，看日志：
    ssh $HOST 'sudo journalctl -u $SERVICE -n 80 --no-pager'"
    ok "已回滚到 $PREV"
    exit 0
fi

# ── 排队：服务器上的锁 ────────────────────────────────────────────────────
#
# 上面那两次「出发/切换前」比对只能**事后发现**冲突；锁是从根上避免并发。
# 两个都留：锁挡住走脚本的人，比对挡住手动 ssh 的人 ——
# 2026-08-29 那次覆盖恰恰是手动敲出来的，锁拦不住它。
#
# 抢不到时把持有者打出来，让人知道该去问谁，而不是干等或强行覆盖。
LOCKDIR="$REMOTE_DIR/.deploy.lock"
LOCK_OWNER="$(whoami)@$(hostname -s) pid=$$ 开始于 $(date '+%F %T')"
#
# **用 mkdir 而不是 flock。** flock 只在持有它的那条 ssh 命令存活期间有效，
# 而我们的 ssh 立刻就返回 —— 锁会在获取的下一毫秒被释放，整个部署期间等于无锁。
# 第一版就是这么写的，看着像有锁，实际一点用都没有。
# mkdir 是原子的：目录已存在就失败，且它会一直在，直到我们显式删掉。
if ! ssh "$HOST" "mkdir '$LOCKDIR' 2>/dev/null" ; then
    HOLDER="$(ssh "$HOST" "cat '$LOCKDIR/owner' 2>/dev/null" || true)"
    AGE="$(ssh "$HOST" "find '$LOCKDIR' -maxdepth 0 -mmin +30 2>/dev/null" || true)"
    if [ -n "$AGE" ]; then
        die "锁已存在且超过 30 分钟，多半是某次部署崩了没放锁：
       持锁者：${HOLDER:-（未知）}
    确认那个进程确实死了之后：ssh $HOST \"rm -rf '$LOCKDIR'\"
    **不自动抢锁** —— 自动抢会把我们要防的那个并发又放回来。"
    fi
    die "有人正在部署，先别推：
       持锁者：${HOLDER:-（未知）}
    等他跑完再来。"
fi
ssh "$HOST" "echo '$LOCK_OWNER' > '$LOCKDIR/owner'" || true
ok "已拿到部署锁"

# **拿到锁的下一行就装 trap。** 中间隔着任何一步，那一步失败就会把锁漏在
# 服务器上，而下一个人看到的是「有人正在部署」—— 一个不存在的人。
WT=""
release_lock() { ssh "$HOST" "rm -rf '$LOCKDIR'" >/dev/null 2>&1 || true; }
cleanup() {
    [ -n "$WT" ] && git worktree remove --force "$WT" >/dev/null 2>&1 || true
    release_lock
}
trap cleanup EXIT

# ── ② 从干净 HEAD 副本构建 ────────────────────────────────────────────────
#
# **不在主工作区打包。** 这个目录常有多个会话同时在改，主工作区里别人未提交的
# 半成品会被一起烘进 jar 推上线，而 git status 里那些行看起来跟你毫无关系。
WT="$(mktemp -d)/deploy-head"
git worktree add -q --detach "$WT" HEAD

TS="$(date +%Y%m%d-%H%M)"
# **名字里带上提交号。** 时间戳答不了「线上跑的是哪个提交」——
# 2026-08-29 为这个问题反推了四轮。带上 SHA 之后 readlink 一眼就是答案。
JAR_NAME="$APP-$TS-$HEAD_SHA.jar"
say "构建中（干净副本，约 2 分钟）…"
( cd "$WT/backend" && mvn -o clean package -pl "$MVN_MODULE" -am -DskipTests -q -Dgit.sha="$HEAD_SHA" ) \
    || die "构建失败"
LOCAL_JAR="$WT/backend/$JAR_IN_REPO"
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

if wait_healthy; then
    ok "起来了"
    # ── **进程在跑的是不是我刚推的那个提交** ─────────────────────────────
    #
    # 切软链与「进程真的在跑它」是两件事：换了包没重启、或重启失败仍跑旧包，
    # 这两种过去只能靠猜，而它们正是「我明明部署了怎么没生效」的两大来源。
    # build.gitSha 与 jar 文件名里的 SHA 来自同一个变量，所以这条判据不会自欺。
    #
    # ⚠️ **pay-svc 没有 actuator**（它只暴露 /internal 与 /callback）。
    # 2026-09-02 第一次用这个脚本发它时就撞了：包切了、进程也换了，
    # 而这一步读不到 gitSha，报「进程在跑的不是这一版」——
    # **一个吓人且不实的结论**。真相是判据不适用，不是部署失败。
    #
    # 对它改用两条能查的事实：软链指向新包 + 进程启动时间在本次部署之后。
    # 那两条合起来同样排除「换了包没重启」与「重启失败跑旧包」。
    if [ "$APP" = "pay-svc" ]; then
        LIVE_JAR="$(ssh "$HOST" "readlink -f '$LINK'")"
        STARTED="$(ssh "$HOST" "sudo ps -eo etimes,args | grep '$LINK_NAME' | grep -v grep | head -1 | awk '{print \$1}'")"
        if [ "$(basename "$LIVE_JAR")" = "$JAR_NAME" ] && [ "${STARTED:-99999}" -lt 300 ]; then
            ok "线上进程确认在跑 $JAR_NAME（起于 ${STARTED}s 前）"
        else
            die "包切过去了，但进程对不上：
       软链 $(basename "$LIVE_JAR")（期望 $JAR_NAME）
       进程已运行 ${STARTED:-?}s（应当不足 300s）
    看：ssh $HOST 'sudo systemctl status $SERVICE'"
        fi
    else
    LIVE_SHA="$(ssh "$HOST" "curl -s '${HEALTH%/health}/info'" \
        | sed -n 's/.*"gitSha":"\([^"]*\)".*/\1/p')"
    if [ "$LIVE_SHA" = "$HEAD_SHA" ]; then
        ok "线上进程确认在跑 $HEAD_SHA"
    else
        die "包切过去了、health 也 200，但**进程在跑的不是这一版**：
       期望 $HEAD_SHA
       实际 ${LIVE_SHA:-（/actuator/info 里没有 gitSha —— 这个包不是走部署流程出来的）}
    多半是重启没真的换进程。看：ssh $HOST 'sudo systemctl status $SERVICE'"
    fi
    fi
else
    die "没等到 health=200。**别走开** —— 现在线上是挂的。
    看日志：ssh $HOST 'sudo journalctl -u $SERVICE -n 80 --no-pager'
    要回滚：ssh $HOST \"sudo ln -sfn $(basename "$BEFORE") '$LINK' && sudo systemctl restart '$SERVICE'\""
fi

# ── ⑥ 保留策略 ───────────────────────────────────────────────────────────
#
# 每个包 86M，2026-08-29 时服务器上已堆了 13 个 / 2.8G。磁盘还宽裕，
# 但没人会回头删。保留最近 5 个 **加上当前软链指向的那个**（即使它已排在
# 5 名之外）—— 少了后半句，某次回滚到旧版之后下一次部署就会把脚下那个删掉。
ssh "$HOST" "cd '$REMOTE_DIR' || exit 0
    cur=\$(readlink shop-app.jar 2>/dev/null)
    ls -1t shop-app-*.jar 2>/dev/null | tail -n +6 | while read -r f; do
        [ \"\$f\" = \"\$cur\" ] && continue
        sudo rm -f -- \"\$f\"
    done" >/dev/null 2>&1 || true

printf '\n\033[32m上线完成\033[0m  %s  ←  %s %s\n' "$JAR_NAME" "$HEAD_SHA" "$HEAD_MSG"
printf '回滚：ssh %s "sudo ln -sfn %s %s && sudo systemctl restart %s"\n' \
    "$HOST" "$(basename "$BEFORE")" "$LINK" "$SERVICE"
