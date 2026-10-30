#!/usr/bin/env bash
# Java21 + disposable local MySQL9 routing/menus; the full Mock vertical scenario is separate.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${FOOD_TEST_JDBC_URL:?Set local MySQL9 information_schema JDBC URL}"
: "${FOOD_TEST_DB_USER:?Load the disposable native test account from private configuration}"
export FOOD_TEST_DISPOSABLE=true
TASK_TESTS='FoodOutboxWiringTest#channelSubscribersDoNotCreateDraftOrNonHandoverDeliveries,MenuServiceTest,FoodCatalogWithdrawalEventTest,FoodChannelFulfillmentEventConsumerTest,FoodServerProducerOutboxTest,DiningKdsEventConsumerTest,ReportCorrectionConsumerTest'
exec mvn -o -f "$TASK_ROOT/backend/pom.xml" \
  -Pfood-embedded,food-verify,commerce-embedded,food-commerce-embedded,retail-commerce-embedded \
  -pl :shop-app,:food-server -am test "-Dtest=$TASK_TESTS" -Dsurefire.failIfNoSpecifiedTests=false
