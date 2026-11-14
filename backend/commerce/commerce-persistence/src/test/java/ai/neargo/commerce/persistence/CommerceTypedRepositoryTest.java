package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class CommerceTypedRepositoryTest {
    @Test
    void everyOwnedTypedRepositoryRoundTripsAllFieldsAndRejectsTenantLeakage() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var u = new JdbcCommerceUnitOfWork(db.ds, new DataSourceTransactionManager(db.ds));
            {
                var row =
                        new ai.neargo.commerce.persistence.catalog.SysBusinessTypeRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                11,
                                12,
                                13L,
                                "v13");
                var repo =
                        new ai.neargo.commerce.persistence.catalog.SysBusinessTypeRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "sys_business_type");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.catalog.SysBusinessEndRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "{\"field\": \"allowed_business_types\"}",
                                "v13",
                                15,
                                16,
                                17,
                                "v17",
                                "client-ref",
                                "acceptance-ref");
                var repo =
                        new ai.neargo.commerce.persistence.catalog.SysBusinessEndRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "sys_business_end");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.catalog.SysIndustryPlanRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                12,
                                "v12",
                                "v13",
                                15);
                var repo =
                        new ai.neargo.commerce.persistence.catalog.SysIndustryPlanRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "sys_industry_plan");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.catalog.SysIndustryPlanVersionRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                11,
                                "v11",
                                "v12",
                                "v13",
                                "v14",
                                16L,
                                "v16",
                                "v17");
                var repo =
                        new ai.neargo.commerce.persistence.catalog.SysIndustryPlanVersionRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "sys_industry_plan_version");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.catalog.SysPlanVersionEndRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                12);
                var repo =
                        new ai.neargo.commerce.persistence.catalog.SysPlanVersionEndRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "sys_plan_version_end");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.merchandise.MallProductRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                "{\"field\": \"media\"}",
                                "{\"field\": \"allowed_business_types\"}",
                                "v15",
                                17);
                var repo =
                        new ai.neargo.commerce.persistence.merchandise.MallProductRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_product");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.merchandise.MallSkuRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                14);
                var repo = new ai.neargo.commerce.persistence.merchandise.MallSkuRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_sku");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.merchandise.MallSkuVersionRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                11,
                                "v11",
                                "v12",
                                14L,
                                15L,
                                "{\"field\": \"allowed_business_types\"}",
                                "{\"field\": \"refund_policy_snapshot\"}",
                                18L,
                                "sku-title",
                                5);
                var repo =
                        new ai.neargo.commerce.persistence.merchandise.MallSkuVersionRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_sku_version");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.merchandise.MallSkuComponentRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                11,
                                "v11",
                                "v12",
                                "v13",
                                "v14",
                                16,
                                17,
                                18L,
                                19,
                                "v19");
                var repo =
                        new ai.neargo.commerce.persistence.merchandise.MallSkuComponentRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_sku_component");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallQuoteRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                "v13",
                                "v14",
                                16L,
                                17L,
                                18L,
                                19L,
                                "v19",
                                "v20",
                                "v21",
                                "{\"field\": \"line_snapshot\"}",
                                "{\"field\": \"conversion_snapshot\"}",
                                "{\"field\": \"schedule_preview_snapshot\"}",
                                26L,
                                27L,
                                28L,
                                29L,
                                "v29");
                var repo = new ai.neargo.commerce.persistence.trade.MallQuoteRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_quote");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallOrderRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                "v13",
                                "v14",
                                "v15",
                                17L,
                                18L,
                                19L,
                                20L,
                                21L,
                                22L,
                                23L,
                                "v23",
                                "v24",
                                "v25",
                                27L,
                                28L,
                                29L,
                                "v29",
                                "v30");
                var repo = new ai.neargo.commerce.persistence.trade.MallOrderRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_order");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallOrderItemRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                13,
                                14L,
                                15L,
                                16L,
                                17L,
                                "{\"field\": \"sku_snapshot\"}");
                var repo = new ai.neargo.commerce.persistence.trade.MallOrderItemRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_order_item");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallOrderComponentRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "{\"field\": \"target_snapshot\"}",
                                14,
                                15L,
                                16L,
                                17L,
                                18L,
                                19,
                                "v19");
                var repo =
                        new ai.neargo.commerce.persistence.trade.MallOrderComponentRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_order_component");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallPaymentRefRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                "v13",
                                15L,
                                "v15",
                                17L,
                                "{\"request\": true}",
                                "proof-requestHash",
                                "{\"result\": true}",
                                "DISPATCHED",
                                "proof-closureReference",
                                "{\"verified\": true}");
                var repo = new ai.neargo.commerce.persistence.trade.MallPaymentRefRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_payment_ref");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallFulfillmentTaskRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                11,
                                "v11",
                                "v12",
                                14,
                                15L,
                                "v15",
                                "v16",
                                18L,
                                "proof-orderNo",
                                "{\"request\": true}",
                                "proof-requestHash",
                                "{\"result\": true}",
                                "DISPATCHED");
                var repo =
                        new ai.neargo.commerce.persistence.trade.MallFulfillmentTaskRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_fulfillment_task");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallShipmentRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "{\"field\": \"shipment_items\"}",
                                "{\"field\": \"address_snapshot\"}",
                                "v12",
                                "v13",
                                "v14",
                                16L,
                                17L);
                var repo = new ai.neargo.commerce.persistence.trade.MallShipmentRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_shipment");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallRefundRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "{\"field\": \"policy_snapshot\"}",
                                14L,
                                15L,
                                16L,
                                "v16",
                                "v17",
                                "v18",
                                "v19",
                                21L);
                var repo = new ai.neargo.commerce.persistence.trade.MallRefundRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_refund");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.MallRefundItemRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                13,
                                14L,
                                15L,
                                "v15",
                                "v16",
                                "{\"field\": \"adjustment_snapshot\"}");
                var repo = new ai.neargo.commerce.persistence.trade.MallRefundItemRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mall_refund_item");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.trade.CommerceEventInboxRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                "v13",
                                15L,
                                16L);
                var repo =
                        new ai.neargo.commerce.persistence.trade.CommerceEventInboxRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "commerce_event_inbox");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionAccountRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                12L,
                                "v12",
                                "v13",
                                "v14");
                var repo =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionAccountRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mch_subscription_account");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription.MchSubscriptionPeriodRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                "v13",
                                "v14",
                                "v15",
                                "v16",
                                "v17",
                                "v18",
                                "v19",
                                21L,
                                22L,
                                23L,
                                24L,
                                25L,
                                26L,
                                27L,
                                28L,
                                29L,
                                30L,
                                31L,
                                "v31",
                                "v32",
                                "v33",
                                "{\"field\": \"source_policy_snapshot\"}");
                var repo =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionPeriodRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mch_subscription_period");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription.MchStoreEndGrantRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                14L,
                                15,
                                "v15",
                                17L,
                                18L,
                                "v18",
                                20L,
                                "v20");
                var repo =
                        new ai.neargo.commerce.persistence.subscription.MchStoreEndGrantRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mch_store_end_grant");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionReservationRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                13L,
                                14L,
                                15L,
                                16L,
                                "v16",
                                "{\"field\": \"source_periods_snapshot\"}",
                                19L);
                var repo =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionReservationRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(
                        row, repo.findById("A", 1).orElseThrow(), "mch_subscription_reservation");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionConversionRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                12L,
                                13L,
                                "v13",
                                "v14",
                                16L,
                                17L,
                                18L,
                                "v18",
                                "v19");
                var repo =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionConversionRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(
                        row, repo.findById("A", 1).orElseThrow(), "mch_subscription_conversion");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionConversionItemRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                11L,
                                12L,
                                13L,
                                14L);
                var repo =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionConversionItemRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(
                        row,
                        repo.findById("A", 1).orElseThrow(),
                        "mch_subscription_conversion_item");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionCreditAccountRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                100L,
                                5L);
                var repo =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionCreditAccountRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(
                        row,
                        repo.findById("A", 1).orElseThrow(),
                        "mch_subscription_credit_account");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionCreditLotRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                110L,
                                90L,
                                5L,
                                "{\"field\": \"parent_provenance\"}",
                                18L);
                var repo =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionCreditLotRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(
                        row, repo.findById("A", 1).orElseThrow(), "mch_subscription_credit_lot");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionCreditLedgerRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                "v11",
                                "v12",
                                "v13",
                                15,
                                16L,
                                17L,
                                18L,
                                "v18",
                                "v19",
                                "v20");
                var repo =
                        new ai.neargo.commerce.persistence.subscription
                                .MchSubscriptionCreditLedgerRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(
                        row, repo.findById("A", 1).orElseThrow(), "mch_subscription_credit_ledger");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.reliability.CommerceOutboxRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                12L,
                                "v12",
                                "{\"field\": \"payload\"}",
                                "v14",
                                16,
                                17L,
                                18,
                                19L,
                                20L);
                var repo =
                        new ai.neargo.commerce.persistence.reliability.CommerceOutboxRepository(
                                db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "commerce_outbox");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.reliability
                                .MchSubscriptionMigrationMapRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                10L,
                                "v10",
                                "v11",
                                "v12",
                                14L,
                                "v14",
                                "{\"field\": \"legacy_snapshot\"}",
                                "v16",
                                18L);
                var repo =
                        new ai.neargo.commerce.persistence.reliability
                                .MchSubscriptionMigrationMapRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(
                        row, repo.findById("A", 1).orElseThrow(), "mch_subscription_migration_map");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.reliability.MchEndScheduleEventRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                12L,
                                "v12",
                                "v13",
                                15L,
                                "{\"field\": \"before_snapshot\"}",
                                "{\"field\": \"after_snapshot\"}",
                                "{\"field\": \"duration_check_snapshot\"}",
                                "v18");
                var repo =
                        new ai.neargo.commerce.persistence.reliability
                                .MchEndScheduleEventRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "mch_end_schedule_event");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
            {
                var row =
                        new ai.neargo.commerce.persistence.reliability.CommerceExpiryNoticeRecord(
                                1L,
                                "A",
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.102"),
                                java.time.LocalDateTime.parse("2026-11-03T00:00:00.103"),
                                "v4",
                                "v5",
                                7L,
                                0,
                                "v8",
                                "v9",
                                "v10",
                                12L,
                                "v12",
                                14L,
                                15L,
                                "v15",
                                17L,
                                "v17",
                                "v18",
                                "v19",
                                "v20",
                                "v21",
                                23,
                                24L,
                                25L,
                                "v25",
                                "v26",
                                28L);
                var repo =
                        new ai.neargo.commerce.persistence.reliability
                                .CommerceExpiryNoticeRepository(db.ds);
                assertThrows(IllegalStateException.class, () -> repo.insert(row));
                u.required(
                        () -> {
                            assertEquals(1L, repo.insert(row));
                            return null;
                        });
                assertEquals(row, repo.findById("A", 1).orElseThrow(), "commerce_expiry_notice");
                assertTrue(repo.findById("B", 1).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> repo.findById("", 1));
            }
        }
    }
}
