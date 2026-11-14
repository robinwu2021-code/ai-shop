#!/usr/bin/env bash
# Bounded related tests. Native fixtures own only UUID databases on local MySQL9.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${INVENTORY_PROJECTION_TEST_URL:?Set local MySQL9 information_schema JDBC URL}"
: "${INVENTORY_PROJECTION_TEST_USER:?Set the native test account}"
: "${INVENTORY_PROJECTION_TEST_PASSWORD:?Load the native password from private configuration}"
export FOOD_INVENTORY_DIRECT_NATIVE=true OUTBOX_SLIM_NATIVE=true
TASK_TESTS='SchemaDriftTest,SchemaGeneratorTest,H2FixtureProjectionTest,PeriodFlowTest#cancelBeforeCutoffOnly,AfterCommitOutsideTransactionTest,AfterCommitDeferredActionsTest,ManagementCommandJournalTest,BrandBindingConcurrencyTest,BrandFinancialActivationTest,BrandFinancialChangeTest,BrandScopeAuthorizationTest,IssuerAuthorityTest,ManagementReadScopeTest,ManagementRepositoryProxyTest,PaymentMerchantOwnershipTest,StoredValueCollectionAuthorityTest,StoredValuePolicyAuthorityTest,FoodOutboxWiringTest,FoodMaintenanceServiceImplTest,FoodServerProducerOutboxTest,RemoteFoodStockSyncDispatcherTest,FoodStockSyncDispatchScopeTest,FoodInventoryDirectSyncNativeMysqlTest,ManagementCommandMigrationNativeMysqlTest,PointsOrderLiveCreationTest,PointsOrderSourceOrdersFlowTest,PointsOrderRecoveryFlowTest,PrintOrderSoftwareFlowTest'
exec mvn -o -f "$TASK_ROOT/backend/pom.xml" \
  -Pfood-embedded,food-verify,commerce-embedded,food-commerce-embedded,retail-commerce-embedded \
  -pl :shop-app,:food-server -am test "-Dtest=$TASK_TESTS" \
  -Dsurefire.heap=3g -Dsurefire.failIfNoSpecifiedTests=false
