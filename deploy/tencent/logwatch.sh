#!/usr/bin/env bash
#
# 本机巡检：磁盘、日志总量、日志增速、outbox 死信。
# 安装位置：/data/app/ai-shop/ops/logwatch.sh
# 由 /etc/cron.d/ai-shop-logwatch 每小时第 7 分跑，输出进 /data/log/ai-shop/ops/logwatch.log。
#
# 2026-09-14 那次盘满，日志从 9-4 起刷了十天没人知道。上限（logback 封顶、journald 200M）
# 只保证日志自己写不满盘；这里管的是另一半 —— **失控能在小时级被发现**。
#
# 超阈值：打一行 WARN / CRIT，并写进 journal（`journalctl -t logwatch`）。
# 主动推送：在 logwatch.env 里配 WEBHOOK_URL（企业微信群机器人格式），同一项 6 小时内只推一次。
# **通道没定之前不配** —— 那时就只有日志与 journal，等于要有人来看。
#
# 阈值是测试档（方案 §6）；转生产只改 logwatch.env 里的数，不改脚本：
#   DISK_WARN=80  DISK_CRIT=90  LOG_TOTAL_MB=1024  RATE_MB_PER_H=50  WEBHOOK_URL=
#   OUTBOX_FAILED_BASELINE=20   （outbox 死信的已知条数，见第 4 节）
#   BINLOG_MAX_MB=1024          （binlog 总量上限，见第 4b 节）
set -euo pipefail

DATA="${DATA:-/data}"
CONF="${CONF:-$DATA/app/ai-shop/ops/logwatch.env}"
if [ -r "$CONF" ]; then
    # shellcheck disable=SC1090
    . "$CONF"
fi
DISK_WARN="${DISK_WARN:-80}"
DISK_CRIT="${DISK_CRIT:-90}"
LOG_TOTAL_MB="${LOG_TOTAL_MB:-1024}"
RATE_MB_PER_H="${RATE_MB_PER_H:-50}"
BINLOG_MAX_MB="${BINLOG_MAX_MB:-1024}"
WEBHOOK_URL="${WEBHOOK_URL:-}"
# 数据库客户端。2026-09-16 切 MySQL 9.7 后，裸 `mysql` 连的是已停的 MariaDB(3306)，
# 这一条会一直报「查不了 outbox」—— 报得对，但它该查的是新库。
MYSQL_CLI="${MYSQL_CLI:-/opt/mysql/current/bin/mysql --defaults-file=/etc/mysql97/my.cnf -uroot}"
OUTBOX_FAILED_BASELINE="${OUTBOX_FAILED_BASELINE:-}"
STATE="${STATE:-$DATA/app/ai-shop/ops/state/logwatch}"
RESEND_MIN=360        # 同一项告警多久再推一次
MIN_INTERVAL=600      # 两次运行间隔短于这个就不算增速：手动补跑一次会把一分钟的量放大 60 倍

mkdir -p "$STATE"
now="$(date +%s)"
stamp="$(date '+%F %T')"
alerts=()

say() {   # say <OK|WARN|CRIT> <项> <说明>
    printf '%s %-4s %-26s %s\n' "$stamp" "$1" "$2" "$3"
    if [ "$1" != OK ]; then alerts+=("$1|$2|$3"); fi
}
# 不到 1M 用 K：全用 M 的话平时每一行都是「0.0M」，量具坏了和流量很小长得一模一样
mb() { awk -v b="$1" 'BEGIN { if (b < 1048576) printf "%.1fK", b / 1024; else printf "%.1fM", b / 1048576 }'; }

# ── 1. 磁盘 ─────────────────────────────────────────────────────────────────
# /data 与 /data/log 将来可能各挂一块盘；现在它们和 / 是同一个文件系统，去重后只报一行
for m in $(df --output=target / "$DATA" "$DATA/log" 2>/dev/null | tail -n +2 | sort -u); do
    p="$(df --output=pcent "$m" | tail -1 | tr -dc '0-9')"
    if   [ "$p" -ge "$DISK_CRIT" ]; then lv=CRIT
    elif [ "$p" -ge "$DISK_WARN" ]; then lv=WARN
    else lv=OK; fi
    say "$lv" "disk $m" "$p%（警告 $DISK_WARN% · 严重 $DISK_CRIT%）"
done

# ── 2. /data/log 总量 ───────────────────────────────────────────────────────
t="$(du -sb "$DATA/log" | cut -f1)"
if [ "$t" -gt $((LOG_TOTAL_MB * 1048576)) ]; then lv=WARN; else lv=OK; fi
say "$lv" "log-total $DATA/log" "$(mb "$t")（上限 ${LOG_TOTAL_MB}M）"

# ── 3. 每个服务的写入速度 ───────────────────────────────────────────────────
# **不能用「目录一小时涨了多少」**：应用日志有总量封顶，洪水里目录很快停在上限，
# 此后新写多少就删多少，目录大小纹丝不动 —— 这把尺在最需要它的时候读数是 0。
# 量的是「写了多少原文」：这段时间新滚出来的归档（gzip -l 读原文大小）+ 当前文件的增量。
#   写入量 = Σ 新归档原文 + 当前文件大小 − 上次记下的当前文件大小
# 没滚动时就是当前文件的增量；滚过的话，上次记下的那部分已经包含在第一个新归档里，减掉正好不重算。
for d in "$DATA"/log/*/*/; do
    d="${d%/}"
    key="$(basename "$(dirname "$d")")/$(basename "$d")"
    sf="$STATE/rate-${key//\//__}"
    cur=0
    while IFS= read -r -d '' f; do
        cur=$((cur + $(stat -c %s "$f")))
    done < <(find "$d" -maxdepth 1 -type f -name '*.log' -print0)

    if [ ! -f "$sf" ]; then
        echo "$cur" > "$sf"
        say OK "rate $key" "首次记录（当前文件 $(mb "$cur")），下一轮起算增速"
        continue
    fi
    elapsed=$((now - $(stat -c %Y "$sf")))
    if [ "$elapsed" -lt "$MIN_INTERVAL" ]; then
        say OK "rate $key" "距上次只有 ${elapsed} 秒，不算（状态不动，留给下一轮）"
        continue
    fi

    last="$(cat "$sf")"; rolled=0; nroll=0
    while IFS= read -r -d '' g; do
        raw="$(gzip -l "$g" 2>/dev/null | awk 'NR == 2 { print $2 }')"
        rolled=$((rolled + ${raw:-0})); nroll=$((nroll + 1))
    done < <(find "$d" -maxdepth 1 -type f -name '*.gz' ! -name 'legacy-*' -newer "$sf" -print0)
    written=$((rolled + cur - last))
    if [ "$written" -lt 0 ]; then written="$cur"; fi   # 被 copytruncate 截过（ops 日志）
    rate=$((written * 3600 / elapsed))
    echo "$cur" > "$sf"

    if [ "$rate" -gt $((RATE_MB_PER_H * 1048576)) ]; then lv=WARN; else lv=OK; fi
    say "$lv" "rate $key" "$(mb "$rate")/h（上限 ${RATE_MB_PER_H}M/h）· 滚出 $nroll 份 · 当前文件 $(mb "$cur")"
done

# ── 4. outbox 死信 ──────────────────────────────────────────────────────────
# 投递器投满上限就把事件转成 FAILED（6a375ac5）。那一步把「无限重试刷满盘」这种
# **响亮的失败**，换成了「事件悄悄进死信」这种**无声的失败** —— 不在这里数，就没人知道。
#
# 判据是「多于基线」而不是「多于 0」：9-14 那 20 条已定不重投、手工转成了 FAILED，
# 写成 > 0 的话装上第一轮就响、此后每轮都响，这条告警就此变成噪音。
# 新的 FAILED 处理完，由人把 logwatch.env 里的基线调上去 —— 与仓库的 known-* 同一个道理。
#
# ⚠️ 查库放在 if 里：本脚本 set -euo pipefail，库连不上时 $(mysql …) 失败会让**整个脚本
# 当场静默退出**，连后面的告警出口都走不到。库连不上本身就该报，不能让它把巡检一起带走。
# （同一个坑 2026-09-11 在 verify-apk.sh 里踩过：防御分支在它该触发的那一刻不可达。）
if f_sys="$($MYSQL_CLI -N -B -e "SELECT COUNT(*) FROM ai_shop.sys_outbox WHERE status='FAILED'" 2>/dev/null)" \
   && f_inv="$($MYSQL_CLI -N -B -e "SELECT COUNT(*) FROM ai_shop_inv.inv_outbox WHERE status='FAILED'" 2>/dev/null)" \
   && [[ "$f_sys" =~ ^[0-9]+$ && "$f_inv" =~ ^[0-9]+$ ]]; then
    f=$((f_sys + f_inv))
    if [ -z "$OUTBOX_FAILED_BASELINE" ]; then
        say WARN "outbox-failed" "现有 $f 条（sys $f_sys · inv $f_inv），基线没设 —— 核过之后在 logwatch.env 写 OUTBOX_FAILED_BASELINE=$f"
    elif [ "$f" -gt "$OUTBOX_FAILED_BASELINE" ]; then
        say WARN "outbox-failed" "$f 条，比基线多 $((f - OUTBOX_FAILED_BASELINE))（sys $f_sys · inv $f_inv）—— 有事件投满上限进了死信"
    else
        say OK "outbox-failed" "$f 条（基线 $OUTBOX_FAILED_BASELINE · sys $f_sys · inv $f_inv）"
    fi
else
    say WARN "outbox-failed" "查不了 outbox（库连不上或表不在）—— 死信有没有新增此刻不知道"
fi

# ── 4b. binlog 总量（2026-09-16 切到 MySQL 后补）───────────────────────────
# **补的是第 2 节看不见的那一块。** 第 2 节量 `/data/log`，而 binlog 落在 `/data/db`——
# 换库带来的最大新增日志类消费者，恰好在那把尺的扫描面之外（量具的扫描面就是结论的覆盖面）。
#
# binlog 自己有 7 天过期 + 单文件 100M 的上限，所以这里不是"防它涨"，而是**盯上限有没有失效**：
# 改配置、大事务（迁移那次单个文件 173M 就超了 100M）、或从库没跟上导致不敢清，都会让它堆起来。
# 按 /data/db/*/mysql-bin.* 通配：将来换实例目录名也照样量得到。
binlog_bytes=0; binlog_n=0
while IFS= read -r -d '' b; do
    binlog_bytes=$((binlog_bytes + $(stat -c %s "$b"))); binlog_n=$((binlog_n + 1))
done < <(find "$DATA"/db -maxdepth 2 -type f -name 'mysql-bin.[0-9]*' -print0 2>/dev/null)
if [ "$binlog_n" = 0 ]; then
    say OK "binlog" "没有 binlog 文件（没开，或还没产生）"
elif [ "$binlog_bytes" -gt $((BINLOG_MAX_MB * 1048576)) ]; then
    say WARN "binlog" "$(mb "$binlog_bytes") / $binlog_n 个（上限 ${BINLOG_MAX_MB}M）—— 过期清理可能没生效，查 binlog_expire_logs_seconds"
else
    say OK "binlog" "$(mb "$binlog_bytes") / $binlog_n 个（上限 ${BINLOG_MAX_MB}M）"
fi

# ── 5. 告警出口 ─────────────────────────────────────────────────────────────
[ "${#alerts[@]}" -gt 0 ] || exit 0

for a in "${alerts[@]}"; do
    IFS='|' read -r lv item msg <<<"$a"
    if [ "$lv" = CRIT ]; then pri=user.crit; else pri=user.warning; fi
    logger -t logwatch -p "$pri" "$lv $item $msg"
done

[ -n "$WEBHOOK_URL" ] || exit 0
send=(); keys=()
for a in "${alerts[@]}"; do
    IFS='|' read -r lv item msg <<<"$a"
    k="$STATE/sent-$(printf '%s %s' "$lv" "$item" | md5sum | cut -c1-12)"
    if [ -z "$(find "$k" -mmin -"$RESEND_MIN" 2>/dev/null)" ]; then
        send+=("$lv $item $msg"); keys+=("$k")
    fi
done
[ "${#send[@]}" -gt 0 ] || exit 0

body="$(printf '%s\n' "【$(hostname) 巡检】" "${send[@]}" \
    | python3 -c 'import json, sys; print(json.dumps({"msgtype": "text", "text": {"content": sys.stdin.read()}}, ensure_ascii=False))')"
# URL 里带着机器人的密钥：只进 curl 参数，不回显、不写日志
if curl -fsS -m 10 -H 'Content-Type: application/json' -d "$body" "$WEBHOOK_URL" >/dev/null; then
    touch "${keys[@]}"   # 推成功才记「已推」，失败的下一轮还会再试
    echo "$stamp 已推送 ${#send[@]} 条"
else
    echo "$stamp 推送失败（curl 退出码 $?），下一轮重试"
fi
