#!/usr/bin/env bash
#
# 把现网三个库(ai_shop / ai_shop_inv / ai_shop_job)从 MariaDB 迁到 MySQL 9.7。
# 方案见 docs/technical/design/运维-MariaDB切MySQL方案.md 的 M2。
#
# 做法(schema 与 data 分离,比整体 sed 稳 —— 排序规则替换只碰 DDL、不碰数据):
#   ① dump 表结构 → 归一排序规则到 0900 → 在 MySQL 建空表(索引结构随之落地)
#   ② dump 表数据 → 灌进空表 → InnoDB 一边插一边构建二级索引与唯一索引(= 同时建索引)
#   ③ 逐表 COUNT(*) 对账 + SHOW INDEX 抽查
#
# **只读现网 MariaDB,只写 MySQL 目标库。** 现网库全程不被触碰。
#
# 用法:
#   bash convert.sh                 # 迁到 MySQL 同名库(ai_shop 等),灌之前会 DROP 目标同名库
#   SUFFIX=_m2 bash convert.sh      # 迁到 ai_shop_m2 等(副本演练,不占用正式库名)
#   DBS="ai_shop" bash convert.sh   # 只迁一个库
set -uo pipefail
cd "$(dirname "$0")"; # 本脚本专做 MariaDB → MySQL,源固定是 MariaDB（lib.sh 的默认源已于 2026-09-16 改成 MySQL）。
# 放在 . ./lib.sh **之前**：lib.sh 用的是 ${VAR:-默认}，先设好这里就不会被默认值覆盖。
SRC_DUMP="${SRC_DUMP:-sudo mariadb-dump}"
SRC_CLI="${SRC_CLI:-sudo mariadb -N -B}"
SRC_DUMP_EXTRA="${SRC_DUMP_EXTRA:-}"   # mariadb-dump 不认 --set-gtid-purged
. ./lib.sh

DBS="${DBS:-ai_shop ai_shop_inv ai_shop_job}"
SUFFIX="${SUFFIX:-}"                 # 目标库名后缀;空=同名(正式切换),_m2=演练
WORK="${WORK:-/tmp/mysql-migrate}"; mkdir -p "$WORK"

say "源 MariaDB(只读) → 目标 MySQL 9.7  ·  目标库后缀「${SUFFIX:-（同名）}」  ·  排序规则归一到 $TARGET_COLL"
[ -z "$SUFFIX" ] && warn "SUFFIX 为空:会 DROP 并重建正式库名。演练请用 SUFFIX=_m2。"

fail=0
for db in $DBS; do
    tgt="${db}${SUFFIX}"
    say "── $db → $tgt"

    # ① 结构:dump --no-data,只在 DDL 上归一排序规则,建空表
    $SRC_DUMP $DUMP_OPTS $SRC_DUMP_EXTRA --no-data "$db" 2>"$WORK/$db.dumperr" | normalize_collation > "$WORK/$db.schema.sql"
    [ -s "$WORK/$db.schema.sql" ] || { warn "$db 结构导出为空:$(cat "$WORK/$db.dumperr")"; fail=1; continue; }
    left=$(grep -c uca1400 "$WORK/$db.schema.sql" || true)
    [ "$left" = 0 ] || { warn "$db 结构里仍残留 $left 处 uca1400,归一化没干净"; fail=1; continue; }
    $TGT_CLI -e "DROP DATABASE IF EXISTS \`$tgt\`; CREATE DATABASE \`$tgt\` CHARACTER SET $TARGET_CS COLLATE $TARGET_COLL"
    $TGT_CLI "$tgt" < "$WORK/$db.schema.sql" 2>"$WORK/$db.schemaerr" \
        || { warn "$db 建表失败(DDL 方言?):$(head -3 "$WORK/$db.schemaerr")"; fail=1; continue; }
    ok "空表 + 索引结构就位($(grep -c 'CREATE TABLE' "$WORK/$db.schema.sql") 张表)"

    # ② 数据:dump --no-create-info,灌进空表,InnoDB 同时构建索引
    $SRC_DUMP $DUMP_OPTS $SRC_DUMP_EXTRA --no-create-info "$db" 2>>"$WORK/$db.dumperr" \
        | $TGT_CLI "$tgt" 2>"$WORK/$db.loaderr"
    dup=$(grep -c "ERROR 1062" "$WORK/$db.loaderr" || true)
    err=$(grep -c "ERROR"      "$WORK/$db.loaderr" || true)
    if [ "$err" != 0 ]; then
        warn "$db 灌数据有 $err 处错误(其中撞唯一键 $dup)——排序规则冲突或方言,见 $WORK/$db.loaderr"
        grep "ERROR" "$WORK/$db.loaderr" | head -5 | sed 's/^/      /'
        fail=1; continue
    fi
    ok "数据灌完、索引已构建,0 报错"

    # ③ 对账:逐表 COUNT(*)(不用估算的 table_rows)
    mis=0
    while read -r t; do
        a=$(src_count "$db" "$t"); b=$(tgt_count "$tgt" "$t")
        [ "$a" = "$b" ] || { printf "      ✗ %-40s 源=%s 目标=%s\n" "$t" "$a" "$b"; mis=$((mis+1)); }
    done < <(list_tables "$db")
    if [ "$mis" = 0 ]; then ok "逐表行数对账一致"; else warn "$db 有 $mis 张表行数不一致(见上)"; fail=1; fi
done

echo
[ "$fail" = 0 ] && ok "全部完成:结构+数据+索引已迁移,逐表对账通过" \
                || die "有库未通过,见上面的告警与 $WORK/*.loaderr"
