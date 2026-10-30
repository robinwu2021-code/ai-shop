#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${FOOD_TEST_JDBC_URL:?Set local MySQL9 information_schema JDBC URL}"
: "${FOOD_TEST_DB_USER:?Load the disposable native test account from private configuration}"
export FOOD_TEST_DISPOSABLE=true
TASK_TESTS='KitchenLifecycleAcceptanceTest,ProductionConsumptionTest,ProductionConsumptionListMapperTest,DiningProductionConsumerTest,DiningKdsEventConsumerTest,DiningKitchenAcceptedConsumerTest,FoodServerProducerOutboxTest'
exec mvn -o -f "$TASK_ROOT/backend/pom.xml" -Pfood-embedded,food-verify \
  -pl :food-core,:food-web,:food-server -am test "-Dtest=$TASK_TESTS" \
  -Dsurefire.failIfNoSpecifiedTests=false
