#!/usr/bin/env bash
#
# 非日志累积物的定期清理。
# 安装位置：/data/app/ai-shop/ops/housekeeping.sh
# 由 /etc/cron.d/ai-shop-housekeeping 每天 04:10 跑，输出进 /data/log/ai-shop/ops/housekeeping.log。
#
# **默认只列清单、不删。** 开删除要显式给 APPLY=1 —— 方案要求先这样跑一周，对过清单再开。
#
#   sudo /data/app/ai-shop/ops/housekeeping.sh           # 看会删什么
#   sudo APPLY=1 /data/app/ai-shop/ops/housekeeping.sh   # 真删
#
# 规则见 docs/technical/design/运维-目录与日志方案.md §4。三条原则：
#   · **只删自己的脚本按固定格式生成的东西。** 名字对不上格式的（手工备份、带标签的 env 副本）
#     一律只报不删 —— 带标签的 env 副本被手册当回滚依据引用，删了手册就断了
#   · 按 /data/<类型>/*/ 通配，将来加业务不用改这里
#   · 「将删」和「删除」走同一段代码：清单里看到什么，开了 APPLY 就删什么
#
# 日志本身不归它管：应用日志 logback 自己封顶，ops 日志归 logrotate，journal 归 journald。
set -euo pipefail

APPLY="${APPLY:-0}"
DATA="${DATA:-/data}"
TMPD="${TMPD:-/tmp}"
KEEP_ENV=5            # 只有时间戳的 env 副本，每个服务留最新几份
KEEP_WEB=3            # 前端部署脚本留的 <站点>.bak-YYYYMMDD-HHMM，每个站点留最新几份
TMP_JAR_MIN=1440      # /tmp 里的部署包放多久算残留（分钟）。短了会删掉正在进行的部署
PREDEPLOY_DAYS=30
INCIDENT_DAYS=90
JAR_WARN=6            # 服务目录里带版本号的 jar 超过几个就报（部署脚本应只留 5 + 当前）

n_del=0; n_rep=0; bytes=0

size_of() { du -sb -- "$1" 2>/dev/null | cut -f1; }
human()   { numfmt --to=iec --suffix=B "${1:-0}" 2>/dev/null || echo "${1:-0}B"; }
report()  { echo "  只报  $1 —— $2"; n_rep=$((n_rep + 1)); }

# drop <路径> <理由>：dry-run 与真删共用，保证清单即动作
drop() {
    local p="$1" why="$2" sz
    if [ -L "$p" ]; then report "$p" "是软链，不碰"; return 0; fi
    sz="$(size_of "$p")"; sz="${sz:-0}"
    if [ "$APPLY" = 1 ]; then
        rm -rf -- "$p"
        echo "  删除  $(human "$sz")  $p（$why）"
    else
        echo "  将删  $(human "$sz")  $p（$why）"
    fi
    n_del=$((n_del + 1)); bytes=$((bytes + sz))
}

# stdin：NUL 分隔的路径 → stdout：按修改时间从新到旧，一行一个
newest_first() { xargs -0 -r stat -c '%Y %n' -- | sort -rn | cut -d' ' -f2-; }

echo "== $(date '+%F %T')  housekeeping  $([ "$APPLY" = 1 ] && echo '【删除模式】' || echo '只列清单（APPLY=1 才删）')"

# ── 1. /tmp 里的部署残留包 ───────────────────────────────────────────────────
# 服务名从「服务目录里指向当前版的软链」认：/data/app/<业务>/<服务>/<服务>.jar
echo "-- /tmp 里的部署包"
svcs=()
for link in "$DATA"/app/*/*/*.jar; do
    [ -L "$link" ] || continue
    [ "$(basename "$link" .jar)" = "$(basename "$(dirname "$link")")" ] || continue
    svcs+=("$(basename "$link" .jar)")
done
mapfile -d '' tmpjars < <(find "$TMPD" -maxdepth 1 -type f -name '*.jar' -print0 2>/dev/null)
for f in "${tmpjars[@]}"; do
    name="$(basename "$f")"; ours=""
    for s in "${svcs[@]}"; do
        case "$name" in "$s"-[0-9]*.jar) ours="$s" ;; esac
    done
    if [ -z "$ours" ]; then
        report "$f" "名字不是任何服务的部署包（$(human "$(size_of "$f")")），看不出是谁的"
    elif [ -n "$(find "$f" -mmin +"$TMP_JAR_MIN")" ]; then
        drop "$f" "$ours 的部署包，超过 $((TMP_JAR_MIN / 60)) 小时"
    fi   # 一天以内的：可能是正在进行的部署，不碰也不报
done

# ── 2. env 副本 ─────────────────────────────────────────────────────────────
echo "-- env 副本（env-backup/）"
ts_re='.*/[^/]+\.env\.bak[-.][0-9]+([-.][0-9]+)*'     # 只有时间戳：.env.bak-0901-1520 / .env.bak.1787145830
for d in "$DATA"/app/*/*/env-backup; do
    [ -d "$d" ] || continue
    mapfile -t keep_order < <(find "$d" -maxdepth 1 -type f -regextype posix-extended -regex "$ts_re" -print0 | newest_first)
    i=0
    for f in "${keep_order[@]}"; do
        i=$((i + 1))
        if [ "$i" -gt "$KEEP_ENV" ]; then drop "$f" "只有时间戳的 env 副本，留最新 $KEEP_ENV 份"; fi
    done
    labeled="$(find "$d" -maxdepth 1 -type f -regextype posix-extended ! -regex "$ts_re" | wc -l)"
    if [ "$labeled" -gt 0 ]; then
        report "$d" "带标签的 $labeled 份不自动删（手册拿它们当回滚依据）"
    fi
done

# ── 3. 前端部署留的备份 ─────────────────────────────────────────────────────
echo "-- 前端备份（web/）"
for w in "$DATA"/app/*/web; do
    [ -d "$w" ] || continue
    for live in "$w"/*/; do
        site="$(basename "$live")"
        case "$site" in *.bak*) continue ;; esac
        mapfile -t order < <(find "$w" -mindepth 1 -maxdepth 1 -type d -regextype posix-extended \
            -regex ".*/${site}\.bak-[0-9]{8}-[0-9]{4}" -print0 | newest_first)
        i=0
        for b in "${order[@]}"; do
            i=$((i + 1))
            if [ "$i" -gt "$KEEP_WEB" ]; then drop "$b" "$site 的部署备份，留最新 $KEEP_WEB 份"; fi
        done
    done
    # 名字不是部署脚本格式的（手工 cp 出来的、打的 tgz）：只报一行汇总
    mapfile -d '' manual < <(find "$w" -mindepth 1 -maxdepth 1 \( -name '*.bak*' -o -name '*.tgz' \) \
        -regextype posix-extended ! -regex '.*\.bak-[0-9]{8}-[0-9]{4}' -print0)
    if [ "${#manual[@]}" -gt 0 ]; then
        total=0; for m in "${manual[@]}"; do s="$(size_of "$m")"; total=$((total + ${s:-0})); done
        report "$w" "${#manual[@]} 份手工备份共 $(human "$total")，名字不是部署脚本的格式：$(for m in "${manual[@]}"; do printf '%s ' "$(basename "$m")"; done)"
    fi
done

# ── 4. 迁移前导出 ───────────────────────────────────────────────────────────
echo "-- 迁移前导出（backup/*/predeploy/，$PREDEPLOY_DAYS 天）"
mapfile -d '' old < <(find "$DATA"/backup/*/predeploy -mindepth 1 -maxdepth 1 -mtime +"$PREDEPLOY_DAYS" -print0 2>/dev/null)
for f in "${old[@]}"; do drop "$f" "超过 $PREDEPLOY_DAYS 天"; done

# ── 5. 事故留档与搬家前的旧日志 ─────────────────────────────────────────────
echo "-- 事故留档与旧日志（$INCIDENT_DAYS 天）"
mapfile -d '' old < <(find "$DATA"/log/*/incident -mindepth 1 -maxdepth 1 -mtime +"$INCIDENT_DAYS" -print0 2>/dev/null)
for f in "${old[@]}"; do drop "$f" "事故留档超过 $INCIDENT_DAYS 天"; done
mapfile -d '' old < <(find "$DATA"/log/*/* -maxdepth 1 -type f -name 'legacy-*.gz' -mtime +"$INCIDENT_DAYS" -print0 2>/dev/null)
for f in "${old[@]}"; do drop "$f" "搬家前的旧日志超过 $INCIDENT_DAYS 天"; done

# ── 6. 只核对不删 ───────────────────────────────────────────────────────────
echo "-- 只核对"
for link in "$DATA"/app/*/*/*.jar; do
    [ -L "$link" ] || continue
    dir="$(dirname "$link")"; svc="$(basename "$link" .jar)"
    [ "$svc" = "$(basename "$dir")" ] || continue
    n="$(find "$dir" -maxdepth 1 -type f -name "$svc-*.jar" | wc -l)"
    if [ "$n" -gt "$JAR_WARN" ]; then
        report "$dir" "$n 个版本 jar，部署脚本应只留 5 + 当前 —— 看看保留逻辑是不是没跑"
    fi
done
for d in "$DATA"/backup/*/legacy; do
    [ -d "$d" ] || continue
    n="$(find "$d" -mindepth 1 -maxdepth 1 | wc -l)"
    if [ "$n" -gt 0 ]; then
        report "$d" "$n 项共 $(human "$(size_of "$d")")，搬家时集中过来的旧 jar 备份，人看过再删"
    fi
done

echo "-- 合计：$([ "$APPLY" = 1 ] && echo 删除 || echo 将删) $n_del 项 $(human "$bytes") · 只报 $n_rep 项"
df -h / "$DATA" 2>/dev/null | awk 'NR==1 || !seen[$0]++' | sed 's/^/   /'
