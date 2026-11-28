#!/usr/bin/env bash
# .61 行业库餐饮表变更（TDD-餐饮域迁移新库 P1-5）。须用有建表权限的账号（root）执行。
#
#   run.sh check <mysql.cnf> [库名]   只看：列出现有表、行数，以及与定稿结构的差异，不改任何东西
#   run.sh apply <mysql.cnf> [库名]   改：核对全部为空 → 备份 → 执行 02-rebuild-food-tables.sql → 按期望结构核对
#
# mysql.cnf 为 MySQL [client] 格式（host、port、user、password）。库名默认 n_industry。
# 备份目录 BACKUP_DIR（默认 ./backups）。MYSQL、MYSQLDUMP 可指定客户端路径。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:?用法: run.sh check|apply <mysql.cnf> [库名]}"
CNF="${2:?缺 mysql.cnf}"
DB="${3:-n_industry}"
MYSQL="${MYSQL:-mysql}"
MYSQLDUMP="${MYSQLDUMP:-mysqldump}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
q() { "$MYSQL" --defaults-extra-file="$CNF" --batch --raw --skip-column-names "$@"; }

ALL=$(cat "$HERE/tables.txt" "$HERE/retired.txt")
IN=$(printf "'%s'," $ALL); IN="${IN%,}"

existing=$(q -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' AND table_name IN ($IN) ORDER BY table_name")
echo "== $DB 里已有的相关表：$(echo "$existing" | grep -c . || true) 张"
nonempty=""
for t in $existing; do
  n=$(q "$DB" -e "SELECT COUNT(*) FROM \`$t\`")
  printf '  %-40s %s 行\n' "$t" "$n"
  [ "$n" != "0" ] && nonempty="$nonempty $t"
done

structure() {
  {
    q -e "SELECT 'column', table_name, column_name, column_type, is_nullable, IFNULL(column_default,'<null>') FROM information_schema.columns WHERE table_schema='$DB' AND table_name IN ($IN) ORDER BY table_name, ordinal_position"
    q -e "SELECT 'unique', table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='$DB' AND table_name IN ($IN) AND non_unique=0 GROUP BY table_name, index_name ORDER BY table_name, index_name"
  } | sort
}
diff_expected() { diff <(sort "$HERE/expected-structure.tsv") <(structure) ; }

if [ "$MODE" = "check" ]; then
  echo "== 与定稿结构的差异（< 定稿要有、现场没有；> 现场有、定稿没有）"
  diff_expected && echo "  无差异"
  [ -n "$nonempty" ] && echo "!! 有数据的表：$nonempty（apply 会拒绝执行）"
  exit 0
fi
[ "$MODE" = "apply" ] || { echo "未知模式 $MODE" >&2; exit 2; }

if [ -n "$nonempty" ]; then
  echo "!! 这些表有数据，本脚本只重建空表，已停止：$nonempty" >&2
  exit 1
fi

stamp=$(date -u +%Y%m%dT%H%M%SZ)
dir="$BACKUP_DIR/food-industry-$stamp"; mkdir -p "$dir"
if [ -n "$existing" ]; then
  # shellcheck disable=SC2086
  "$MYSQLDUMP" --defaults-extra-file="$CNF" --single-transaction --skip-lock-tables --set-gtid-purged=OFF "$DB" $existing > "$dir/$DB.sql"
  grep -q 'CREATE TABLE' "$dir/$DB.sql" || { echo "!! 备份里没有建表语句，已停止" >&2; exit 1; }
  if grep -q '^INSERT' "$dir/$DB.sql"; then echo "!! 备份里有数据行，与行数核对不符，已停止" >&2; exit 1; fi
fi
echo "== 备份：$dir（表结构，全部为空表）"

q "$DB" < "$HERE/02-rebuild-food-tables.sql"
echo "== 已执行 02-rebuild-food-tables.sql"

if diff_expected; then
  echo "== 核对通过：$(wc -l < "$HERE/tables.txt" | tr -d ' ') 张表的列与唯一键与定稿一致，退役表已删"
else
  echo "!! 核对不通过，见上面的差异；可用备份恢复：$dir/$DB.sql" >&2
  exit 1
fi
