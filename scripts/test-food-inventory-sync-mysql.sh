#!/usr/bin/env bash
# Explicit native MySQL9 connection. Tests create/drop only their own inv_projection_it_<uuid> database.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${INVENTORY_PROJECTION_TEST_URL:?Set the local MySQL9 information_schema JDBC URL}"
: "${INVENTORY_PROJECTION_TEST_USER:?Set the dedicated native test account}"
: "${INVENTORY_PROJECTION_TEST_PASSWORD:?Load the native test password from private configuration}"
export INVENTORY_PROJECTION_NATIVE=true
exec mvn -o -f "$TASK_ROOT/backend/pom.xml" -pl :inventory-svc,:shop-inventory -am test \
  -Dtest=StockProjectionNativeMysqlTest,StockProjectionPreflightTest,FoodStockProjectionRemoteTest,InventoryArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false
