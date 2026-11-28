#!/usr/bin/env bash
# .61 行业库餐饮表变更（TDD-餐饮域迁移新库 P1-5）。须用有建表权限的账号（root）执行。
#
#   run.sh check <mysql.cnf> [库名]   只看：列出现有表、行数，以及与定稿结构的差异，不改任何东西
#   run.sh apply <mysql.cnf> [库名]   改：核对餐饮独有的表全部为空 → 备份 → 执行 02（重建餐饮表）与 03（改共用表的列）
#                                     → 补共用表缺的唯一键 → 按期望结构核对
#
# 与美业共用的表（shared.txt）在 .61 上已有美业数据：只改列定义、补唯一键，不重建、不动数据。
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
SHARED=" $(tr '\n' ' ' < "$HERE/shared.txt")"
IN=$(printf "'%s'," $ALL); IN="${IN%,}"

existing=$(q -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' AND table_name IN ($IN) ORDER BY table_name")
echo "== $DB 里已有的相关表：$(echo "$existing" | grep -c . || true) 张"
nonempty=""; shared_rows=""
for t in $existing; do
  n=$(q "$DB" -e "SELECT COUNT(*) FROM \`$t\`")
  case "$SHARED " in
    *" $t "*) printf '  %-40s %s 行（与美业共用，只改列不重建）\n' "$t" "$n"; [ "$n" != "0" ] && shared_rows="$shared_rows $t" ;;
    *) printf '  %-40s %s 行\n' "$t" "$n"; [ "$n" != "0" ] && nonempty="$nonempty $t" ;;
  esac
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
  [ -n "$nonempty" ] && echo "!! 餐饮独有的表有数据：$nonempty（apply 会拒绝执行）"
  [ -n "$shared_rows" ] && echo "   共用表有数据：$shared_rows（apply 只改列、补唯一键，数据保留）"
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
  # 餐饮独有的表为空，备份里只应有共用表的数据行；有数据的共用表必须在备份里有数据行
  for t in $shared_rows; do
    grep -q "^INSERT INTO \`$t\`" "$dir/$DB.sql" || { echo "!! 备份里没有 $t 的数据行，已停止" >&2; exit 1; }
  done
  if grep '^INSERT INTO' "$dir/$DB.sql" | grep -vqE "^INSERT INTO \`($(echo $SHARED | tr ' ' '|'))\`"; then
    echo "!! 备份里有餐饮独有表的数据行，与行数核对不符，已停止" >&2; exit 1
  fi
fi
echo "== 备份：$dir（餐饮独有的表为空；共用表连数据一起备份：${shared_rows:- 无}）"

q "$DB" < "$HERE/02-rebuild-food-tables.sql"
echo "== 已执行 02-rebuild-food-tables.sql"
for t in $SHARED; do
  if [ -z "$(q -e "SELECT 1 FROM information_schema.tables WHERE table_schema='$DB' AND table_name='$t'")" ]; then
    # 共用表现场还没有：按定稿建（从重建脚本以外的定稿取）
    echo "!! 共用表 $t 不存在，本脚本不新建共用表，已停止" >&2; exit 1
  fi
done
q "$DB" < "$HERE/03-alter-shared-tables.sql"
echo "== 已执行 03-alter-shared-tables.sql"
# 共用表缺的唯一键：按期望结构补（现场同名同列的不动）
while IFS=$'\t' read -r kind t name cols; do
  [ "$kind" = "unique" ] || continue
  case "$SHARED " in *" $t "*) ;; *) continue ;; esac
  [ "$name" = "PRIMARY" ] && continue
  # 同名索引可能是普通索引（设计稿把 uk_ 开头的建成了普通 KEY），或列不同：先删再建成唯一键
  have=$(q -e "SELECT CONCAT(MIN(non_unique),':',GROUP_CONCAT(column_name ORDER BY seq_in_index)) FROM information_schema.statistics WHERE table_schema='$DB' AND table_name='$t' AND index_name='$name'")
  if [ "$have" != "0:$cols" ]; then
    [ -n "$have" ] && [ "$have" != "NULL" ] && q "$DB" -e "ALTER TABLE \`$t\` DROP INDEX \`$name\`"
    q "$DB" -e "ALTER TABLE \`$t\` ADD UNIQUE KEY \`$name\` ($(echo "$cols" | sed 's/[^,]*/`&`/g'))"
    echo "   补唯一键 $t.$name ($cols)"
  fi
done < "$HERE/expected-structure.tsv"

if diff_expected; then
  echo "== 核对通过：$(wc -l < "$HERE/tables.txt" | tr -d ' ') 张表的列与唯一键与定稿一致，退役表已删"
else
  echo "!! 核对不通过，见上面的差异；可用备份恢复：$dir/$DB.sql" >&2
  exit 1
fi
