#!/usr/bin/env bash
#
# 把后端测试**对着真 MySQL 9.7** 跑一遍（默认只跑关键链路）。
#
# ═══ 为什么需要它 ═══
#
# 日常全量跑在 H2 上，而 H2 既不是 MariaDB 也不是 MySQL。2026-09-16 把生产切到
# MySQL 之后，第一次把全量对着真库跑，1896 条里红了 359 条 —— 但逐条归因之后，
# **真正的方言不兼容只有 2 条**：
#
#   · DATEADD(...)        —— H2/SQL Server 有，MySQL 与 MariaDB 都没有。
#                            测试夹具里用了它，意味着那条验资金不变量的用例**从没在真库上跑过**。
#   · json_valid CHECK    —— 真库拦下了一行坏 JSON，而 H2 根本不带 CHECK 约束。
#
# 其余 350 多条全是**测试之间互相污染**：它们共用一个库，而 H2 每次给的是全新内存库。
# 那不是 MySQL 的问题，是 H2 一直在替它们兜着。
#
# 所以这个脚本**默认只跑关键链路**（下单 / 支付 / 库存 / 结算）：真正的方言差异收益递减很快，
# 而把 281 个测试类逐个改造成「自带干净库」的成本，明显大于它还能再捞出来的东西。
#
# ═══ 用法 ═══
#
#   scripts/test-on-mysql.sh                    # 关键链路
#   PATTERN='OrderFlowTest' scripts/test-on-mysql.sh
#   PATTERN='*' scripts/test-on-mysql.sh        # 全量（会红一片，见上）
#
# 要一个 SSH 隧道通到服务器的 3307；脚本自己开、自己关。
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${HOST:-soukmind-tx}"
PORT="${PORT:-13307}"
SUFFIX="${SUFFIX:-_autotest}"
# 关键链路：钱与货经过的那几条。其余留在 H2。
PATTERN="${PATTERN:-*OrderFlowTest,*PayFlowTest,*Inventory*Test,*SettleFlowTest,*FundInvariant*Test,MapperSmokeTest}"

say() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

# ── ① 建一个干净的库 ───────────────────────────────────────────────
#
# **必须是「生产档」（--level required）。** DevSeeder 在应用启动时种测试账号，
# 而它的幂等判据是「cmt_community 有没有数据」—— 灌了测试档（已有社区数据）的话，
# 它会**整段跳过**，运营账号一个都不种，于是 246 处 opsLogin 全部「请先登录」。
# 2026-09-16 第一次跑就是这么红了 35% 的，看起来像 MySQL 不兼容，其实是档位用错。
say "① 在 $HOST 上建干净库（生产档）"
ssh -o ConnectTimeout=20 "$HOST" "bash -s" <<REMOTE || die "建库失败"
set -e
cd /data/app/ai-shop/ops/migrate
M="/opt/mysql/current/bin/mysql --defaults-file=/etc/mysql97/my.cnf -uroot"
sudo \$M -e "DROP DATABASE IF EXISTS ai_shop$SUFFIX; DROP DATABASE IF EXISTS ai_shop_inv$SUFFIX; DROP DATABASE IF EXISTS ai_shop_job$SUFFIX;"
TARGET_COLL=utf8mb4_unicode_520_ci FRESH_SEED=1 bash build-fresh.sh --level required --suffix "$SUFFIX" >/dev/null 2>&1
PW=\$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-22)
sudo \$M -e "DROP USER IF EXISTS 'autotest'@'%'; CREATE USER 'autotest'@'%' IDENTIFIED WITH caching_sha2_password BY '\$PW';
GRANT ALL ON \\\`ai_shop$SUFFIX\\\`.* TO 'autotest'@'%';
GRANT ALL ON \\\`ai_shop_inv$SUFFIX\\\`.* TO 'autotest'@'%';
GRANT ALL ON \\\`ai_shop_job$SUFFIX\\\`.* TO 'autotest'@'%'; FLUSH PRIVILEGES;"
printf '%s' "\$PW" > /tmp/.autotest.pw
REMOTE
ok "ai_shop$SUFFIX 就绪"

# 口令由服务器生成，取回来只落一个临时文件，不回显
PWF="$(mktemp)"; trap 'rm -f "$PWF"; pkill -f "ssh -f -N -L $PORT:" 2>/dev/null || true' EXIT
ssh -o ConnectTimeout=20 "$HOST" 'cat /tmp/.autotest.pw; rm -f /tmp/.autotest.pw' > "$PWF"

# ── ② 隧道 ────────────────────────────────────────────────────────
pkill -f "ssh -f -N -L $PORT:" 2>/dev/null || true
ssh -f -N -L "$PORT:127.0.0.1:3307" "$HOST"
sleep 2
nc -z 127.0.0.1 "$PORT" || die "隧道没通"
ok "隧道 $PORT → $HOST:3307"

# ── ③ 跑 ──────────────────────────────────────────────────────────
#
# 三个覆盖缺一不可：
#   sql.init.mode=never  —— schema-test.sql 是 H2 方言，灌进 MySQL 当场炸
#   flyway.enabled=false —— 库已经由 build-fresh 建好，不需要也不该再迁一次
#   driver-class-name    —— h2db profile 把它设成了 org.h2.Driver
say "② 跑：$PATTERN"
Q='?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8'
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
cd backend
# **先清报告目录。** 不清的话下面那句 awk 会把上一轮残留的报告一起数进去 ——
# 2026-09-16 第一次跑这个脚本，明明只跑了 18 个类，合计却报出 1896 条。
# 量具读的是目录，不是这一次的运行。
rm -rf shop-app/target/surefire-reports
set +e
SUITE_DB=mysql mvn -o -pl shop-app -am test \
    -Dtest="$PATTERN" -Dsurefire.failIfNoSpecifiedTests=false \
    -Dspring.datasource.url="jdbc:mysql://127.0.0.1:$PORT/ai_shop$SUFFIX$Q" \
    -Dspring.datasource.username=autotest \
    -Dspring.datasource.password="$(cat "$PWF")" \
    -Dspring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver \
    -Dspring.sql.init.mode=never -Dspring.flyway.enabled=false
rc=$?
set -e

echo
awk -F'[:,]' '/Tests run/{t+=$2; f+=$4; e+=$6} END {printf "  合计 %d 条 · 失败 %d · 错误 %d\n", t, f, e}' \
    shop-app/target/surefire-reports/*.txt 2>/dev/null || true
[ "$rc" = 0 ] && ok "全绿" || printf '  \033[33m!\033[0m 有红的 —— 先按「方言 / 测试互相污染 / 环境」三类归因，别一上来就改生产代码\n'
exit $rc
