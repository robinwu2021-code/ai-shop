#!/usr/bin/env bash
# .61 行业库美业表变更（TDD-美业域迁移新库 B-6）。须用有建表权限的账号（root）执行。
#
#   run.sh check <mysql.cnf> [库名]   只看：列出现有美业表与行数、共用指派表的现状，以及与定稿结构的差异，不改任何东西
#   run.sh apply <mysql.cnf> [库名]   改：核对美业表全部为空 → 备份 → 02 建美业表 → 03 补共用指派表的两列（还没有时）
#                                     → 04 触发器 → 按期望结构核对
#
# 与餐饮共用的指派表 ind_assignment 只补列、改注释，不重建、不动数据；它不存在时停止（应先跑餐饮的变更脚本）。
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
SHARED=ind_assignment
q() { "$MYSQL" --defaults-extra-file="$CNF" --batch --raw --skip-column-names "$@"; }

TABLES=$(cat "$HERE/tables.txt")
IN=$(printf "'%s'," $TABLES $SHARED); IN="${IN%,}"

existing=$(q -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' AND table_name IN ($IN) AND table_name<>'$SHARED' ORDER BY table_name" < /dev/null)
echo "== $DB 里已有的美业表：$(echo "$existing" | grep -c . || true) 张"
nonempty=""
for t in $existing; do
  n=$(q "$DB" -e "SELECT COUNT(*) FROM \`$t\`" < /dev/null)
  printf '  %-40s %s 行\n' "$t" "$n"
  [ "$n" != "0" ] && nonempty="$nonempty $t"
done
shared_exists=$(q -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB' AND table_name='$SHARED'" < /dev/null)
shared_cols=$(q -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$SHARED' AND column_name IN ('employment_assignment_no','planned_assignment_no')" < /dev/null)
echo "== 共用指派表 $SHARED：$( [ "$shared_exists" = 1 ] && echo "存在，美业两列已有 $shared_cols 列" || echo "不存在")"

structure() {
  {
    q -e "SELECT 'column', table_name, column_name, column_type, is_nullable, IFNULL(column_default,'<null>') FROM information_schema.columns WHERE table_schema='$DB' AND table_name IN ($IN) ORDER BY table_name, ordinal_position" < /dev/null
    q -e "SELECT 'unique', table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='$DB' AND table_name IN ($IN) AND non_unique=0 GROUP BY table_name, index_name ORDER BY table_name, index_name" < /dev/null
    q -e "SELECT 'trigger', event_object_table, trigger_name FROM information_schema.triggers WHERE trigger_schema='$DB' AND event_object_table IN ($IN) ORDER BY event_object_table, trigger_name" < /dev/null
  } | sort
}
diff_expected() { diff <(sort "$HERE/expected-structure.tsv") <(structure) ; }

if [ "$MODE" = "check" ]; then
  echo "== 与定稿结构的差异（< 定稿要有、现场没有；> 现场有、定稿没有）"
  diff_expected && echo "  无差异"
  [ -n "$nonempty" ] && echo "!! 美业表有数据：$nonempty（apply 会拒绝执行）"
  exit 0
fi
[ "$MODE" = "apply" ] || { echo "未知模式 $MODE" >&2; exit 2; }

[ -n "$nonempty" ] && { echo "!! 这些美业表有数据，本脚本只重建空表，已停止：$nonempty" >&2; exit 1; }
[ "$shared_exists" = 1 ] || { echo "!! 共用指派表 $SHARED 不存在，应先跑餐饮的变更脚本，已停止" >&2; exit 1; }
case "$shared_cols" in 0|2) ;; *) echo "!! $SHARED 上美业两列只有一列，现场与预期不符，已停止" >&2; exit 1 ;; esac

stamp=$(date -u +%Y%m%dT%H%M%SZ)
dir="$BACKUP_DIR/beauty-industry-$stamp"; mkdir -p "$dir"
# shellcheck disable=SC2086
"$MYSQLDUMP" --defaults-extra-file="$CNF" --single-transaction --skip-lock-tables --set-gtid-purged=OFF --triggers \
  "$DB" $existing $SHARED > "$dir/$DB.sql" < /dev/null
grep -q "CREATE TABLE \`$SHARED\`" "$dir/$DB.sql" || { echo "!! 备份里没有 $SHARED 的建表语句，已停止" >&2; exit 1; }
shared_rows=$(q "$DB" -e "SELECT COUNT(*) FROM \`$SHARED\`" < /dev/null)
if [ "$shared_rows" != "0" ]; then
  grep -q "^INSERT INTO \`$SHARED\`" "$dir/$DB.sql" || { echo "!! 备份里没有 $SHARED 的数据行，已停止" >&2; exit 1; }
fi
echo "== 备份：$dir（美业表为空；$SHARED $shared_rows 行，连数据一起备份）"

q "$DB" < "$HERE/02-create-beauty-tables.sql"
echo "== 已执行 02-create-beauty-tables.sql"
if [ "$shared_cols" = 0 ]; then
  q "$DB" < "$HERE/03-alter-shared-table.sql"
  echo "== 已执行 03-alter-shared-table.sql"
else
  echo "== $SHARED 已有美业两列，跳过 03"
fi
q "$DB" < "$HERE/04-triggers.sql"
echo "== 已执行 04-triggers.sql"

if diff_expected; then
  echo "== 核对通过：$(wc -l < "$HERE/tables.txt" | tr -d ' ') 张美业表与 $SHARED 的列、唯一键、触发器与定稿一致"
else
  echo "!! 核对不通过，见上面的差异；可用备份恢复：$dir/$DB.sql" >&2
  exit 1
fi
