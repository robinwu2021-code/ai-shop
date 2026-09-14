#!/usr/bin/env bash
#
# 本机巡检：磁盘、日志总量、日志增速。
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
WEBHOOK_URL="${WEBHOOK_URL:-}"
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
mb() { awk -v b="$1" 'BEGIN { printf "%.1fM", b / 1048576 }'; }

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

# ── 4. 告警出口 ─────────────────────────────────────────────────────────────
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
