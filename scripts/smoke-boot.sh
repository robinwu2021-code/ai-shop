#!/usr/bin/env bash
# 启动冒烟：用**生产的 profile 与开关**把 jar 真起一次，看到 "Started" 才算过。
#
# 为什么单测覆盖不了这件事：测试跑的是默认装配（matchIfMissing=true 那一半），
# 而生产跑的常常是另一半。2026-09-02 一天之内撞了三次：
#   · shop-app：一个 app service 挂着 embedded 条件而生产是 standalone → 上线即挂，回滚
#   · pay-svc：待远程 Port 的桩把 Object.equals 也拦了 → 容器自己用不了它
#   · pay-svc：某个 Port 在 pay 侧已有本地实现，桩成了第二个 bean → 两个 bean 撞车
# 三个都是**装配**问题，1600 条测试一个都测不到。
#
# 用法：bash scripts/smoke-boot.sh [shop-app|pay-svc|all]
set -uo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
TARGET="${1:-all}"
FAILED=0

smoke() {
  local name="$1" jar="$2" port="$3"; shift 3
  [ -f "$jar" ] || { echo "✗ $name: jar 不存在（先 mvn package）：$jar"; FAILED=1; return; }
  local log; log="$(mktemp)"
  "$JAVA" -jar "$jar" --server.port="$port" --spring.flyway.enabled=false "$@" > "$log" 2>&1 &
  local pid=$!
  # 最多等 90 秒。起得来的话通常 10 秒内
  for _ in $(seq 1 90); do
    grep -qE "Started \w+ in [0-9.]+ seconds" "$log" && break
    grep -q "APPLICATION FAILED TO START" "$log" && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if grep -qE "Started \w+ in" "$log"; then
    echo "✓ $name: $(grep -oE 'Started \w+ in [0-9.]+ seconds' "$log" | tail -1)"
  else
    echo "✗ $name 起不来："
    # 只打 Description 那一段 —— 它说的是「缺哪个 bean」，比整条栈有用
    grep -A4 "Description:" "$log" | head -6 | sed 's/^/    /'
    grep -E "Caused by:.*(UnsupportedOperation|NoSuchBean|但 found|but .* were found)" "$log" \
      | head -2 | cut -c1-200 | sed 's/^/    /'
    FAILED=1
  fi
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  rm -f "$log"
}

# shop-app：生产的 profile 组合 + standalone（生产 shop-app.env 里就是这个值）
if [ "$TARGET" = "all" ] || [ "$TARGET" = "shop-app" ]; then
  smoke shop-app backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar 18097 \
    --spring.profiles.active=api,ops,job \
    --shop.pay.deployment=standalone \
    --spring.datasource.url='jdbc:h2:mem:smoke1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'
fi

# pay-svc：**只带 MySQL 驱动**，不能用 H2 —— 用连不上的 MySQL + 不因此失败，
# 验的是「bean 装不装得起来」，不是「数据库通不通」
if [ "$TARGET" = "all" ] || [ "$TARGET" = "pay-svc" ]; then
  smoke pay-svc backend/pay/pay-svc/target/pay-svc-0.1.0-SNAPSHOT.jar 18098 \
    --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/nonexistent?connectTimeout=1000' \
    --spring.datasource.username=smoke --spring.datasource.password=smoke \
    --spring.datasource.hikari.initialization-fail-timeout=-1
fi

[ "$FAILED" = 0 ] && echo "冒烟通过 —— 可以上传" || { echo "冒烟失败 —— **不要上传**"; exit 1; }
