#!/usr/bin/env bash
# Reuse the persistent local native MySQL test databases; no Docker or remote hosts.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_ENV="$TASK_ROOT/backend/.env.local"
[[ -f "$TASK_ENV" ]] || { echo 'Missing backend/.env.local; see the local MySQL migration guide.' >&2; exit 1; }
set -a
source "$TASK_ENV"
set +a
[[ "${FOOD_HOST_SMOKE_JDBC_URL:-}" =~ ^jdbc:mysql://127[.]0[.]0[.]1:3308/food_host_smoke(_[a-z0-9_]{1,32})?$ ]] || {
  echo 'Refusing a database other than the dedicated local MySQL test database.' >&2; exit 1;
}
[[ "${FOOD_HOST_SMOKE_DB_USER:-}" == food_local_test && -n "${FOOD_HOST_SMOKE_DB_PASSWORD:-}" ]] || {
  echo 'Dedicated local test account required.' >&2; exit 1;
}
if [[ -z "${FOOD_MYSQL_RUN_TAG:-}" && "$FOOD_HOST_SMOKE_JDBC_URL" == jdbc:mysql://127.0.0.1:3308/food_host_smoke_* ]]; then
  export FOOD_MYSQL_RUN_TAG="${FOOD_HOST_SMOKE_JDBC_URL##*/food_host_smoke_}"
fi
if [[ -n "${FOOD_MYSQL_RUN_TAG:-}" ]]; then
  [[ "$FOOD_MYSQL_RUN_TAG" =~ ^[a-z0-9_]{1,32}$ ]] || { echo 'Invalid isolated local test database tag.' >&2; exit 1; }
  export FOOD_HOST_SMOKE_JDBC_URL="jdbc:mysql://127.0.0.1:3308/food_host_smoke_${FOOD_MYSQL_RUN_TAG}"
fi
export FOOD_HOST_SMOKE_DISPOSABLE=true
TASK_TESTS="${1:-FoodEmbeddedStartupIT}"
exec mvn -o -f "$TASK_ROOT/backend/pom.xml" \
  -Pfood-embedded,commerce-embedded,food-commerce-embedded,retail-commerce-embedded \
  -pl shop-app -am test "-Dtest=$TASK_TESTS" -Dsurefire.failIfNoSpecifiedTests=false
