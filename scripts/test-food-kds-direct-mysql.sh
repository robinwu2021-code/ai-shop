#!/usr/bin/env bash
# Java21 + disposable local MySQL9 only; credentials come from private exported configuration.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${FOOD_TEST_JDBC_URL:?Set local MySQL9 information_schema or mysql JDBC URL}"
: "${FOOD_TEST_DB_USER:?Load the disposable native test account from private configuration}"
export FOOD_TEST_DISPOSABLE=true
TASK_TESTS='FoodStationRuntimeTest,FoodStationRuntimePersistenceTest,FoodPrimaryDatabaseTest#nativeKitchenUsesOriginalFrozenRoutesAndDurableTransactionalServiceEvents,FoodKdsSubscriptionTest,DiningKdsEventConsumerTest,KdsStreamAcceptanceTest,FoodServerProducerOutboxTest'
exec mvn -o -f "$TASK_ROOT/backend/pom.xml" -Pfood-embedded,food-verify \
  -pl :food-core,:food-web,:food-server -am test "-Dtest=$TASK_TESTS" \
  -Dsurefire.failIfNoSpecifiedTests=false
