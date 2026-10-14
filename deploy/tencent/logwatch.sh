#!/usr/bin/env bash
#
# 本机巡检：磁盘、日志总量、日志增速、outbox 死信、binlog 总量、**服务是否还活着**。
# 安装位置：/data/app/ai-shop/ops/logwatch.sh
# 由 /etc/cron.d/ai-shop-logwatch 每小时第 7 分跑，输出进 /data/log/ai-shop/ops/logwatch.log。
#
# 2026-09-14 那次盘满，日志从 9-4 起刷了十天没人知道。上限（logback 封顶、journald 200M）
# 只保证日志自己写不满盘；这里管的是另一半 —— **失控能在小时级被发现**。
#
# 超阈值：打一行 WARN / CRIT，并写进 journal（`journalctl -t logwatch`）。
# 主动推送：同一项 6 小时内只推一次。两条通道，配哪条走哪条，都配就都走：
#   ALERT_MAIL_TO  收件人邮箱（SMTP 凭据不在这儿，见下面 MAIL_ENV_FILE）
#   WEBHOOK_URL    企业微信群机器人
# **一条都不配时只有日志与 journal，等于要有人主动来看。**
#
# 阈值是测试档（方案 §6）；转生产只改 logwatch.env 里的数，不改脚本：
#   DISK_WARN=80  DISK_CRIT=90  LOG_TOTAL_MB=1024  RATE_MB_PER_H=50  WEBHOOK_URL=
#   OUTBOX_FAILED_BASELINE=20   （outbox 死信的已知条数，见第 4 节）
#   BINLOG_MAX_MB=1024          （binlog 总量上限，见第 4b 节）
#   TRAFFIC_PACKAGE_GB=500  TRAFFIC_WARN_PCT=70  TRAFFIC_PERIOD_DAY=18   （公网出流量，见第 4d 节）
#   IMG_PEAK_MBPS=4  IMG_PEAK_MINUTES=10                                 （图片出口带宽峰值，同上）
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
# 邮件通道。ALERT_MAIL_TO 是收件人（不是凭据，可以进 logwatch.env）。
#
# **SMTP 凭据不在这儿，也不复制一份** —— 运行时直接去读应用已经在用的那份
# （M365，shop-app.env 里的 MAIL_HOST/PORT/USERNAME/PASSWORD/FROM）。
# 理由：这台机器上已经有一套验证过能发信的凭据，再抄一份就是多一个要轮换的地方，
# 而且抄的过程本身就是一次泄露机会。
ALERT_MAIL_TO="${ALERT_MAIL_TO:-}"
# 要巡的服务与探针。空则跳过该项。
# 用 ${VAR-默认} 而不是 ${VAR:-默认}：后者在 VAR="" 时也取默认，于是**关不掉**这一项，
# 消融时想单独验某个分支会验错对象（实测已栽过一次）。
HEALTH_UNITS="${HEALTH_UNITS-ai-shop ai-shop-job ai-shop-pay mysql97 nginx}"
HEALTH_HTTP="${HEALTH_HTTP-http://127.0.0.1:8081/actuator/health=200 http://127.0.0.1:8083/internal/pay/fee-rules=401}"
HEALTH_MYSQL="${HEALTH_MYSQL-1}"
MAIL_ENV_FILE="${MAIL_ENV_FILE:-$DATA/app/ai-shop/shop-app/shop-app.env}"
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

# ── 4c. 服务还活着没有（2026-09-16 补）──────────────────────────────────────
#
# **这一节此前完全不存在**，而它才是最常出事的那一类。
# 判据不是推测：2026-09-16 12:29 mysqld 被 OOM 杀、Requires= 把三个应用服务
# 一起带停 81 秒，而事故前 12:07 那轮巡检报的是一整屏 OK ——
# **在那 81 秒里它报的还会是一整屏 OK**，因为服务死了磁盘不涨、日志不涨。
#
# 更糟的是第 3 节那把尺：服务一死，日志速率归零，读作「OK 0.0K/h」。
# **死掉的服务比活着的看起来更健康。**
#
# 一小时一轮对 81 秒的中断仍然太粗，它换不来「立刻知道」；
# 它换来的是把「挂了一整夜」变成「挂了一小时」——而在这一节存在之前，
# 那个数是「永远不知道」。
#
# **失败要复查一次再报。** 部署本身就会让服务离线十几秒，
# 巡检正好撞上就报 CRIT 是误报；而误报会训练人忽略这个通道，
# 那比没有告警更坏（§11.2 记过同一个形状）。
recheck() {   # recheck <命令...> —— 先试一次，失败等 12 秒再试一次
    "$@" >/dev/null 2>&1 && return 0
    sleep 12
    "$@" >/dev/null 2>&1
}

for u in $HEALTH_UNITS; do
    # `|| true` 不能省：`set -e` 下 is-active 对「服务不在」返回非零，整个脚本会当场退出 ——
    # 而那恰恰是唯一有东西要报的时刻。消融时这条把分支①②整个吞掉了。
    st="$(systemctl is-active "$u" 2>/dev/null || true)"
    case "$st" in
        active)
            say OK "svc $u" "active" ;;
        activating|deactivating|reloading)
            # 在途状态：等一轮再判，别把一次正常重启报成事故
            sleep 12
            st2="$(systemctl is-active "$u" 2>/dev/null || true)"
            if [ "$st2" = active ]; then say OK "svc $u" "active（探到时正在 $st，复查已就绪）"
            else say CRIT "svc $u" "**$st2**（12 秒前是 $st）—— 起不来，看 journalctl -u $u"; fi ;;
        *)
            say CRIT "svc $u" "**$st** —— 服务不在了。看 systemctl status $u 与 journalctl -u $u -n 50" ;;
    esac
done

for probe in $HEALTH_HTTP; do
    url="${probe%=*}"; want="${probe##*=}"
    # 同理：连不上时 curl 返回非零，不加 `|| true` 脚本就死在这儿
    got="$(curl -s -o /dev/null -w '%{http_code}' -m 8 "$url" 2>/dev/null || true)"
    if [ "$got" != "$want" ]; then
        sleep 12
        got="$(curl -s -o /dev/null -w '%{http_code}' -m 8 "$url" 2>/dev/null || true)"
    fi
    if [ "$got" = "$want" ]; then
        say OK "http $(basename "$url")" "$got"
    elif [ "$got" = 000 ]; then
        # 000 与「返回了别的码」要分开说：前者是进程没起/端口不通，后者是应用起来了但不健康
        say CRIT "http $(basename "$url")" "**连不上**（期望 $want）—— 进程没起或端口不通：$url"
    else
        say CRIT "http $(basename "$url")" "**$got**（期望 $want）—— 容器起来了但不健康：$url"
    fi
done

if [ -n "$HEALTH_MYSQL" ]; then
    if recheck $MYSQL_CLI -N -e 'SELECT 1'; then
        say OK "mysql" "查得动"
    else
        say CRIT "mysql" "**连不上或查不动** —— 三个服务都靠它，看 systemctl status mysql97 与 /data/log/infra/mysql97/error.log"
    fi
fi

# ── 4d. 公网出流量（2026-09-25 图片改走应用服务器后补，ADR-026）──────────────
# 图片从 COS 直连改成经这台机器（img.hxmall.top）之后，出流量吃的是轻量服务器的月流量包
# （500 GB，每月 18 日起算）与 5 Mbps 峰值带宽。两件事要在小时级被发现：
#   a) 本期累计出流量 > 流量包的 TRAFFIC_WARN_PCT%   → 该考虑切到图片服务器（shop.media.delivery=direct）
#   b) 图片出口最近一小时里超过 IMG_PEAK_MBPS 的分钟数 ≥ IMG_PEAK_MINUTES → 带宽快被图片吃满
#
# **量的是 nginx 日志里的 $body_bytes_sent，不是网卡计数器**：网卡的 tx 把内网流量也算进去了
# （backup-to-cos.sh 经内网往 COS 传备份），实测开机以来 15 GB 而流量包只记了 2.8 GB，
# 拿它判会天天误报。公网流量全都经过 nginx，日志口径与流量包一致（略少：不含响应头与 TLS 开销）。
# 权威数在控制台（轻量服务器 → 流量包），这里只负责「够早地发现」。
#
# 窗口 = 上次跑到这次之间的每一分钟（最多两天，读当前文件与 .1）—— 漏跑一次不丢数，下一轮补上。
TRAFFIC_PACKAGE_GB="${TRAFFIC_PACKAGE_GB:-500}"
TRAFFIC_WARN_PCT="${TRAFFIC_WARN_PCT:-70}"
TRAFFIC_PERIOD_DAY="${TRAFFIC_PERIOD_DAY:-18}"
NGINX_LOG_DIR="${NGINX_LOG_DIR-/var/log/nginx}"
IMG_LOG_NAME="${IMG_LOG_NAME:-img.access.log}"
IMG_PEAK_MBPS="${IMG_PEAK_MBPS:-4}"
IMG_PEAK_MINUTES="${IMG_PEAK_MINUTES:-10}"
if [ -n "$NGINX_LOG_DIR" ] && [ -d "$NGINX_LOG_DIR" ]; then
    # 本期起点：最近一个「每月 TRAFFIC_PERIOD_DAY 日」
    if [ "$(date +%-d)" -ge "$TRAFFIC_PERIOD_DAY" ]; then
        period="$(date +%Y-%m)-$(printf %02d "$TRAFFIC_PERIOD_DAY")"
    else
        period="$(date -d "$(date +%Y-%m-01) -1 day" +%Y-%m)-$(printf %02d "$TRAFFIC_PERIOD_DAY")"
    fi
    pf="$STATE/egress-period"; af="$STATE/egress-acc"; lf="$STATE/egress-last-run"
    if [ "$(cat "$pf" 2>/dev/null)" != "$period" ]; then echo "$period" > "$pf"; echo 0 > "$af"; fi
    last_run="$(cat "$lf" 2>/dev/null || echo $((now - 3600)))"
    # 从上次那一分钟的下一分钟，到上一整分钟（当前这一分钟还没写完，留给下一轮）
    from=$(( (last_run / 60 + 1) * 60 )); to=$(( (now / 60 - 1) * 60 ))
    if [ $((to - from)) -gt 172800 ]; then from=$((to - 172800)); fi
    hour_from=$(( to - 3540 ))
    keys="$(t=$from; while [ "$t" -le "$to" ]; do LC_ALL=C date -d "@$t" '+%d/%b/%Y:%H:%M'; t=$((t + 60)); done)"
    hour_keys="$(t=$hour_from; while [ "$t" -le "$to" ]; do LC_ALL=C date -d "@$t" '+%d/%b/%Y:%H:%M'; t=$((t + 60)); done)"
    if [ -n "$keys" ]; then
        read -r added peak over < <(
            for f in "$NGINX_LOG_DIR"/*access.log "$NGINX_LOG_DIR"/*access.log.1; do
                [ -r "$f" ] || continue
                # 用 if 不用 case：case 的「模式)」写在 $( ) 里，bash 会把那个 ) 当成替换的结尾
                b="$(basename "$f")"; tag=-
                if [ "$b" = "$IMG_LOG_NAME" ] || [ "$b" = "$IMG_LOG_NAME.1" ]; then tag=IMG; fi
                awk -v tag="$tag" '{ print tag, substr($4, 2, 17), $10 }' "$f"
            done | LC_ALL=C awk -v keys="$keys" -v hkeys="$hour_keys" -v lim="$IMG_PEAK_MBPS" '
                BEGIN { n = split(keys, a, "\n"); for (i = 1; i <= n; i++) want[a[i]] = 1
                        n = split(hkeys, a, "\n"); for (i = 1; i <= n; i++) hour[a[i]] = 1 }
                ($3 ~ /^[0-9]+$/) && ($2 in want) { sum += $3 }
                ($1 == "IMG") && ($3 ~ /^[0-9]+$/) && ($2 in hour) { img[$2] += $3 }
                END { peak = 0; over = 0
                      for (m in img) { r = img[m] * 8 / 60 / 1000000; if (r > peak) peak = r; if (r > lim) over++ }
                      printf "%d %.2f %d\n", sum, peak, over }')
        acc=$(( $(cat "$af") + added ))
        echo "$acc" > "$af"
        echo "$now" > "$lf"
        pkg=$(( TRAFFIC_PACKAGE_GB * 1073741824 ))
        pct=$(( acc * 100 / pkg ))
        if [ "$pct" -ge "$TRAFFIC_WARN_PCT" ]; then lv=WARN; else lv=OK; fi
        say "$lv" "egress" "本期（$period 起）公网出流量 $(mb "$acc") / ${TRAFFIC_PACKAGE_GB}G = ${pct}%（阈值 ${TRAFFIC_WARN_PCT}%）· 本轮 +$(mb "$added")"
        if [ "$over" -ge "$IMG_PEAK_MINUTES" ]; then lv=WARN; else lv=OK; fi
        say "$lv" "img-peak" "图片出口近一小时峰值 ${peak} Mbps · 超 ${IMG_PEAK_MBPS} Mbps 的分钟 ${over}（阈值 ${IMG_PEAK_MINUTES}）"
    fi
else
    say OK "egress" "未配 NGINX_LOG_DIR，跳过"
fi

# ── 5. 告警出口 ─────────────────────────────────────────────────────────────
[ "${#alerts[@]}" -gt 0 ] || exit 0

for a in "${alerts[@]}"; do
    IFS='|' read -r lv item msg <<<"$a"
    if [ "$lv" = CRIT ]; then pri=user.crit; else pri=user.warning; fi
    logger -t logwatch -p "$pri" "$lv $item $msg"
done

# 一条通道都没配就到此为止（结果仍在 logwatch.log 与 journal 里）
if [ -z "$WEBHOOK_URL" ] && [ -z "$ALERT_MAIL_TO" ]; then exit 0; fi
send=(); keys=()
for a in "${alerts[@]}"; do
    IFS='|' read -r lv item msg <<<"$a"
    k="$STATE/sent-$(printf '%s %s' "$lv" "$item" | md5sum | cut -c1-12)"
    if [ -z "$(find "$k" -mmin -"$RESEND_MIN" 2>/dev/null)" ]; then
        send+=("$lv $item $msg"); keys+=("$k")
    fi
done
[ "${#send[@]}" -gt 0 ] || exit 0

# 最坏的那一级进标题：手机锁屏上只看得到标题，**标题不说清就等于没告警**
worst=WARN
for a in "${alerts[@]}"; do case "$a" in CRIT*) worst=CRIT ;; esac; done
text="$(printf '%s\n' "${send[@]}")"

delivered=0

# ── 企业微信群机器人 ──
if [ -n "$WEBHOOK_URL" ]; then
    body="$(printf '%s\n' "【$(hostname) 巡检】" "${send[@]}" \
        | python3 -c 'import json, sys; print(json.dumps({"msgtype": "text", "text": {"content": sys.stdin.read()}}, ensure_ascii=False))')"
    # URL 里带着机器人的密钥：只进 curl 参数，不回显、不写日志
    if curl -fsS -m 10 -H 'Content-Type: application/json' -d "$body" "$WEBHOOK_URL" >/dev/null; then
        delivered=1; echo "$stamp 已推送企业微信 ${#send[@]} 条"
    else
        echo "$stamp 企业微信推送失败（curl 退出码 $?），下一轮重试"
    fi
fi

# ── 邮件 ──
#
# 凭据由 python 自己去 $MAIL_ENV_FILE 里取，**不经过 shell 变量、不进命令行、不落日志**。
# 不用 `set -a; . env`：那条路会把含 `&` 的值截断（JDBC URL 栽过），而且会把
# 整份 env 灌进本进程的环境，`ps e` 就能看见。
if [ -n "$ALERT_MAIL_TO" ]; then
    if MAIL_ENV_FILE="$MAIL_ENV_FILE" ALERT_MAIL_TO="$ALERT_MAIL_TO" \
       ALERT_SUBJECT="[$worst] $(hostname) 巡检 · $(printf '%s' "${send[0]}" | cut -c1-60)" \
       ALERT_COUNT="${#send[@]}" \
       python3 "$(dirname "$0")/logwatch-mail.py" <<<"$text"; then
        delivered=1; echo "$stamp 已发邮件 ${#send[@]} 条 → $ALERT_MAIL_TO"
    else
        echo "$stamp 邮件发送失败（退出码 $?），下一轮重试"
    fi
fi

# **有一条通道送达才记「已推」** —— 全失败时下一轮还会再试。
# 反过来（先记后送）会把一条真告警永久吞掉六小时。
[ "$delivered" = 1 ] && touch "${keys[@]}"
