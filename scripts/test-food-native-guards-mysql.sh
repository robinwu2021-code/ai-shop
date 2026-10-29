#!/usr/bin/env bash
# Native-only narrow guards use UUID databases, never the persistent host fixture.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ -f "$TASK_ROOT/backend/.env.local" ]] || { echo 'Missing local MySQL test configuration.' >&2; exit 1; }
set -a
source "$TASK_ROOT/backend/.env.local"
set +a
[[ "${FOOD_HOST_SMOKE_JDBC_URL:-}" =~ ^jdbc:mysql://127[.]0[.]0[.]1:3308/food_host_smoke(_[a-z0-9_]{1,32})?$ ]] || {
  echo 'Dedicated local MySQL configuration required.' >&2; exit 1;
}
[[ "${FOOD_HOST_SMOKE_DB_USER:-}" == food_local_test && -n "${FOOD_HOST_SMOKE_DB_PASSWORD:-}" ]] || {
  echo 'Dedicated fixture account required.' >&2; exit 1;
}
export FOOD_TEST_DISPOSABLE=true
export FOOD_TEST_JDBC_URL=jdbc:mysql://127.0.0.1:3308/information_schema
export FOOD_TEST_DB_USER="$FOOD_HOST_SMOKE_DB_USER"
export FOOD_TEST_DB_PASSWORD="$FOOD_HOST_SMOKE_DB_PASSWORD"
TASK_TESTS='BundleServiceTest#simultaneousMemberUseAndBundleRegistrationCannotBothCommitOnMySql,RecipeServiceTest#nativeConcurrentDefinitionUpdatesHaveOneWinnerAndOneConflict+nativeAdjustmentChecksRejectMalformedActionQuantityAndOrder,RecipePublicationServiceTest#nativeConcurrentPublicationsHaveOneWinner'
TASK_TESTS="${1:-$TASK_TESTS}"
exec mvn -o -f "$TASK_ROOT/backend/pom.xml" -Pfood-embedded -pl :food-core -am test \
  "-Dtest=$TASK_TESTS" -Dsurefire.failIfNoSpecifiedTests=false
