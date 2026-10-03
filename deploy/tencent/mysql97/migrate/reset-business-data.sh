#!/usr/bin/env bash
#
# 把**现网**的演示业务数据清掉，只留配置。给「从演示数据切到真实经营」用。
#
# ═══ 与 build-fresh.sh 的分工 ═══
#
# build-fresh 是**另起一个新库**（DROP + 重建），不能对现网跑。
# 这个脚本是**就地清**：按 tiers.conf 的分级，TEST 档的表清空，REQUIRED 档一张都不碰。
#
# ═══ 默认不删 ═══
#
#   bash reset-business-data.sh            # 只列清单（默认）
#   APPLY=1 bash reset-business-data.sh    # 真删
#
# 照 housekeeping.sh 那套：先 dry-run 看清单，对过了再加 APPLY=1。
# 不可逆的动作不该由一条命令顺手做掉。
set -euo pipefail
cd "$(dirname "$0")"

DB="${DB:-ai_shop}"
CONF="${CONF:-./tiers.conf}"
APPLY="${APPLY:-0}"
M="/opt/mysql/current/bin/mysql --defaults-file=/etc/mysql97/my.cnf -uroot -N -B"

say()  { printf '\033[36m›\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

. ./lib.sh   # tier_of()

# ── ① 先确认今天有备份 ───────────────────────────────────────────────
#
# **这一条不是仪式。** 清掉的是生产数据，而「清完发现清错了」是没有回头路的 ——
# 唯一的回头路就是那份备份。没有今天的备份就不许跑。
# ⚠️ 要用 `sudo test`：/data/backup 是 root 700，普通用户的 `[ -f ]` 永远是 false。
# 第一次写成裸 `[ -f ]`，于是备份好好地躺在那里、脚本却说「今天还没有备份」——
# 报错方向朝着「更安全」，但它仍然是个假判据：它测的是「我能不能看见」，
# 不是「备份在不在」。这种判据平时看不出毛病，真出事时会让人以为备份没了。
BK="/data/backup/ai-shop/db/${DB}-$(date +%Y%m%d).sql.gz"
sudo test -f "$BK" || die "今天还没有 $DB 的备份（找不到 $BK）—— 先跑 /data/app/ai-shop/ops/backup-to-cos.sh"
BKSZ=$(sudo stat -c%s "$BK")
# 空文件也能通过 `test -f`。备份至少得有内容 —— 今天这份 8.2M，10KB 是宽松下界。
[ "$BKSZ" -gt 10240 ] || die "备份只有 $BKSZ 字节，不能拿它当退路"
ok "今天的备份在：$BK（$(numfmt --to=iec "$BKSZ")）"

# ── ② 分档 ───────────────────────────────────────────────────────────
say "按 tiers.conf 分档（$DB）"
KEEP=(); WIPE=(); RUNTIME=()
for t in $(sudo $M -e "SELECT table_name FROM information_schema.tables
                        WHERE table_schema='$DB' AND table_type='BASE TABLE'"); do
    case "$(tier_of "$CONF" "$DB" "$t")" in
        required|region|accounts) KEEP+=("$t") ;;
        skip)                     RUNTIME+=("$t") ;;
        test)
            # ── 与分级表无关的硬保护 ──────────────────────────────
            # **不管 tiers.conf 怎么写，这几类一律不清。**
            # 2026-09-16 第一次出清单就撞上了：flyway 历史表当时一处都没登记，
            # 默认落进 TEST 档，于是 flyway_schema_history（265 行）被列进待清 ——
            # 清掉它应用下次启动会从 V1 重建，直接毁库。
            # 分级表是人维护的名单，人会漏；这道保护不看名单，只看表名。
            case "$t" in
                *flyway*|*schema_history*)
                    warn "$t 被分到 TEST 档 —— 硬保护拦下（Flyway 历史清不得）"; KEEP+=("$t") ;;
                *) WIPE+=("$t") ;;
            esac
            ;;
    esac
done

# 扫描面断言：分不出档就会全落进某一边，而那一边不管是哪边都是错的
[ "${#KEEP[@]}"  -gt 20 ] || die "保留档只分出 ${#KEEP[@]} 张 —— tiers.conf 没读到？"
[ "${#WIPE[@]}"  -gt 20 ] || die "待清档只分出 ${#WIPE[@]} 张 —— 分档逻辑坏了？"
ok "保留 ${#KEEP[@]} 张 · 待清 ${#WIPE[@]} 张 · 运行时 ${#RUNTIME[@]} 张"

# ── ③ 清单 ───────────────────────────────────────────────────────────
say "待清的表里，现在有数据的："
total=0; nonempty=0
for t in "${WIPE[@]}"; do
    n=$(sudo $M -e "SELECT COUNT(*) FROM \`$DB\`.\`$t\`" 2>/dev/null || echo 0)
    if [ "${n:-0}" -gt 0 ]; then
        printf '    %-34s %s 行\n' "$t" "$n"
        total=$((total + n)); nonempty=$((nonempty + 1))
    fi
done
echo "    ── 合计 $nonempty 张表 · $total 行"

say "运行时表（**本脚本不动**，它们是演示活动留下的痕迹，要不要清由人决定）："
for t in "${RUNTIME[@]}"; do
    n=$(sudo $M -e "SELECT COUNT(*) FROM \`$DB\`.\`$t\`" 2>/dev/null || echo 0)
    [ "${n:-0}" -gt 0 ] && printf '    %-34s %s 行\n' "$t" "$n"
done || true
warn "其中 sys_outbox 里有 9-14 事故那 20 条 FAILED —— 那是留档，清掉就没了"

# ── ④ 删 ─────────────────────────────────────────────────────────────
if [ "$APPLY" != 1 ]; then
    echo
    warn "只列了清单，什么都没删。对过之后用 APPLY=1 再跑一次。"
    exit 0
fi

say "开始清（APPLY=1）"
for t in "${WIPE[@]}"; do
    sudo $M -e "TRUNCATE TABLE \`$DB\`.\`$t\`" 2>/dev/null \
        || sudo $M -e "DELETE FROM \`$DB\`.\`$t\`"   # 有视图/权限问题时退回 DELETE
done

# ── ⑤ 回读 ───────────────────────────────────────────────────────────
#
# **删完要自己数一遍。** TRUNCATE 不报错不等于清干净了：
# 名字拼错、表名大小写、权限不足都可能让某一句静默跳过。
say "回读"
left=0
for t in "${WIPE[@]}"; do
    n=$(sudo $M -e "SELECT COUNT(*) FROM \`$DB\`.\`$t\`" 2>/dev/null || echo 0)
    [ "${n:-0}" -gt 0 ] && { printf '    ★ %-32s 还剩 %s 行\n' "$t" "$n"; left=$((left + 1)); }
done || true
[ "$left" = 0 ] && ok "待清的 ${#WIPE[@]} 张表全为 0" || die "$left 张表没清干净"

# 保留档必须纹丝不动 —— 这是「清错了没有」的判据，不是走过场
say "保留档抽查（这几张空了就是清错了）"
for t in prd_category sys_function_point sys_role_point prd_spu_std sys_region; do
    n=$(sudo $M -e "SELECT COUNT(*) FROM \`$DB\`.\`$t\`" 2>/dev/null || echo 0)
    [ "${n:-0}" -gt 0 ] && printf '    %-24s %s 行 ✓\n' "$t" "$n" || die "$t 空了 —— 清错了，立刻用备份恢复"
done
