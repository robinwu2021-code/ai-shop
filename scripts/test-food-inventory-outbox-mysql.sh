#!/usr/bin/env bash
# Real source transactions + SQL Outbox + separate inventory JVM on owned UUID MySQL9 schemas.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${INVENTORY_PROJECTION_TEST_URL:?Set the local MySQL9 information_schema JDBC URL}"
: "${INVENTORY_PROJECTION_TEST_USER:?Set the native test account with disposable schema permissions}"
: "${INVENTORY_PROJECTION_TEST_PASSWORD:?Load the native test password from private configuration}"
export FOOD_INVENTORY_OUTBOX_NATIVE=true
exec mvn -o -f "$TASK_ROOT/backend/pom.xml" \
  -Pfood-embedded,food-verify,commerce-embedded,food-commerce-embedded,retail-commerce-embedded \
  -pl :shop-app -am test -Dtest=FoodInventoryOutboxNativeMysqlTest \
  -Dsurefire.failIfNoSpecifiedTests=false
