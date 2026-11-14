#!/usr/bin/env bash
# 一次性：在本机从零建出后端可用的 MySQL 9.7 库（ai-shop-mysql97 @ 127.0.0.1:3308）。
#
# 为什么要绕一圈：CORE 迁移 V1–V574 用了 MariaDB 专有排序规则 utf8mb4_uca1400_ai_ci（MySQL 不认），
# 而 V575 起的生成列写法 MariaDB 不支持。所以：MariaDB 跑到 V574 → 导出转换 → 导入 MySQL → 由后端 Flyway 从 V575 继续。
# 做法与 docs/technical/餐饮本地MySQL测试环境.md 一致：只转导出 DDL 的排序规则与 CHECK 名，不改迁移文件/checksum，不伪造成功记录。
#
# 前置：已 build 出 backend/shop-app/target/shop-app-*.jar；Docker 在跑。
# 已存在 ai-shop-mysql97 容器时直接退出，不覆盖已有库。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${TMPDIR:-/tmp}/ai-shop-bootstrap-db"; mkdir -p "$WORK"
JAR=$(ls "$ROOT"/backend/shop-app/target/shop-app-*.jar | head -1)
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"

if docker ps -a --format '{{.Names}}' | grep -qx ai-shop-mysql97; then
  echo "ai-shop-mysql97 已存在，跳过（不覆盖已有库）"; exit 0; fi

echo "› ① 临时 MariaDB 12 @3307"
docker ps -a --format '{{.Names}}' | grep -qx ai-shop-mariadb || \
docker run -d --name ai-shop-mariadb -p 3307:3306 -e MARIADB_ROOT_PASSWORD=root -e MARIADB_DATABASE=ai_shop \
  -e MARIADB_USER=ai_shop -e MARIADB_PASSWORD=ai_shop -e TZ=Asia/Shanghai mariadb:12 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_uca1400_ai_ci >/dev/null
docker start ai-shop-mariadb >/dev/null
until docker exec ai-shop-mariadb mariadb -uai_shop -pai_shop -e "select 1" >/dev/null 2>&1; do sleep 2; done

echo "› ② 在 MariaDB 上跑 CORE 迁移（V575 处启动失败、进程退出，属预期 —— 库存/报表等其余迁移链因此不在 MariaDB 上跑）"
SHOP_DB_USER=ai_shop SHOP_DB_PASS=ai_shop "$JAVA" -jar "$JAR" --spring.profiles.active=api,ops --server.port=18080 \
  "--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/ai_shop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  > "$WORK/maria-migrate.log" 2>&1 || true
V=$(docker exec ai-shop-mariadb mariadb -uai_shop -pai_shop -N ai_shop -e "select max(version+0) from flyway_schema_history where success=1")
[ "$V" = 574 ] || { echo "✗ MariaDB 没到 V574（当前 $V），见 $WORK/maria-migrate.log"; exit 1; }

echo "› ③ 导出（排除 V575 部分建出的表与失败记录）并转换"
docker exec ai-shop-mariadb mariadb -uroot -proot -N ai_shop -e "delete from flyway_schema_history where version+0>574"
EXCL=$(for t in sv_settlement_bill sv_settlement_bill_item; do printf -- "--ignore-table=ai_shop.%s " $t; done)
docker exec ai-shop-mariadb mariadb-dump -uroot -proot --single-transaction --no-create-db --skip-add-locks \
  --skip-comments --hex-blob $EXCL ai_shop > "$WORK/maria.sql"
perl "$HERE/maria-to-mysql.pl" < "$WORK/maria.sql" > "$WORK/mysql.sql"

echo "› ④ MySQL 9.7 @3308（参数对齐 deploy/tencent/mysql97/my.cnf）并导入"
docker run -d --name ai-shop-mysql97 -p 3308:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ai_shop \
  -e MYSQL_USER=ai_shop -e MYSQL_PASSWORD=ai_shop -e TZ=Asia/Shanghai -v ai-shop-mysql97-data:/var/lib/mysql \
  --restart unless-stopped mysql:9.7 --sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --skip-log-bin >/dev/null
until docker exec ai-shop-mysql97 mysql -uai_shop -pai_shop -e "select 1" >/dev/null 2>&1; do sleep 2; done
docker exec -i ai-shop-mysql97 mysql -uroot -proot --default-character-set=utf8mb4 ai_shop < "$WORK/mysql.sql"

echo "› ⑤ 逐表行数对账"
bad=0
for t in $(docker exec ai-shop-mariadb mariadb -uroot -proot -N ai_shop -e "show tables" | grep -vxE 'sv_settlement_bill|sv_settlement_bill_item'); do
  a=$(docker exec ai-shop-mariadb mariadb -uroot -proot -N ai_shop -e "select count(*) from \`$t\`")
  b=$(docker exec ai-shop-mysql97 mysql -uroot -proot -N ai_shop -e "select count(*) from \`$t\`" 2>/dev/null)
  [ "$a" = "$b" ] || { echo "  ✗ $t maria=$a mysql=$b"; bad=1; }
done
[ $bad = 0 ] || exit 1

docker stop ai-shop-mariadb >/dev/null
echo "✓ 完成：MySQL 9.7 @3308 已到 V574。下一步 start-backend.sh，Flyway 会从 V575 接着跑。"
echo "  临时 MariaDB 已停（ai-shop-mariadb），确认无用后可自行删除。"
