#!/usr/bin/env bash
# 前台启动后端（api+ops 两个面，:8080），连本机 MySQL 9.7 @3308。仅限本机开发。
#   SHOP_OPS_SECOND_FACTOR=off —— 关运营登录邮箱验证码（本机邮件是桩收不到码；prod profile 下 off 会拒绝启动）
#   shop.seed.enabled=true     —— 空库时灌演示账号，口令「<用户名>123」（admin/admin123）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT/backend"
ENV_FILE="${AI_SHOP_BACKEND_ENV:-$HOME/work/env/backend/.env.local}"
[ -f "$ENV_FILE" ] && { set -a; . "$ENV_FILE"; set +a; }
export SHOP_DB_USER="${SHOP_DB_USER:-ai_shop}" SHOP_DB_PASS="${SHOP_DB_PASS:-ai_shop}" SHOP_OPS_SECOND_FACTOR=off
JAR=$(ls shop-app/target/shop-app-*.jar | head -1)
exec "$(/usr/libexec/java_home -v 21)/bin/java" -jar "$JAR" \
  --spring.profiles.active=api,ops --server.port=8080 --shop.seed.enabled=true \
  "--spring.datasource.url=jdbc:mysql://127.0.0.1:3308/ai_shop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
