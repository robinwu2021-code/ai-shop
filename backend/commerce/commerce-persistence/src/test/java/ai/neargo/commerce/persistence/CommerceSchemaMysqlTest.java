package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;

class CommerceSchemaMysqlTest {
    @Test
    void ownsThirtySixTablesAndIndependentHistory() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            assertEquals(
                    36,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",
                            Integer.class,
                            db.name));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE"
                                    + " constraint_schema=?",
                            Integer.class,
                            db.name));
        }
    }

    @Test
    void sourceConversionAndEventIdentitiesAreTenantScoped() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            for (var factory :
                    java.util.List.<java.util.function.Function<String, String>>of(
                            Rows::mch_subscription_period,
                            Rows::mch_subscription_conversion_item,
                            Rows::commerce_event_inbox,
                            Rows::commerce_outbox)) {
                db.jdbc.execute(factory.apply("A"));
                assertThrows(
                        DuplicateKeyException.class, () -> db.jdbc.execute(factory.apply("A")));
                db.jdbc.execute(factory.apply("B"));
            }
        }
    }

    @Test
    void creditBalanceCannotBeOverReserved() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            db.jdbc.execute(Rows.mch_subscription_credit_account("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE mch_subscription_credit_account SET reserved_minor=1"
                                            + " WHERE tenant_no='A'"));
        }
    }

    @Test
    void everyCandidateFieldHasTypedPhysicalOrExplicitExternalMapping() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            assertEquals(
                    14,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "sys_business_type"));
            assertEquals(
                    20,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "sys_business_end"));
            assertEquals(
                    15,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "sys_industry_plan"));
            assertEquals(
                    18,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "sys_industry_plan_version"));
            assertEquals(
                    12,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "sys_plan_version_end"));
            assertEquals(
                    17,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_product"));
            assertEquals(
                    14,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_sku"));
            assertEquals(
                    20,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_sku_version"));
            assertEquals(
                    20,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_sku_component"));
            assertEquals(
                    30,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_quote"));
            assertEquals(
                    31,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_order"));
            assertEquals(
                    18,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_order_item"));
            assertEquals(
                    20,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_order_component"));
            assertEquals(
                    23,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_payment_ref"));
            assertEquals(
                    23,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_fulfillment_task"));
            assertEquals(
                    17,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_shipment"));
            assertEquals(
                    26,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_refund"));
            assertEquals(
                    18,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mall_refund_item"));
            assertEquals(
                    16,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "commerce_event_inbox"));
            assertEquals(
                    15,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_account"));
            assertEquals(
                    37,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_period"));
            assertEquals(
                    21,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_store_end_grant"));
            assertEquals(
                    19,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_reservation"));
            assertEquals(
                    20,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_conversion"));
            assertEquals(
                    14,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_conversion_item"));
            assertEquals(
                    12,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_credit_account"));
            assertEquals(
                    18,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_credit_lot"));
            assertEquals(
                    21,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_credit_ledger"));
            assertEquals(
                    20,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "commerce_outbox"));
            assertEquals(
                    18,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_subscription_migration_map"));
            assertEquals(
                    19,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "mch_end_schedule_event"));
            assertEquals(
                    28,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=?"
                                    + " AND table_name=?",
                            Integer.class,
                            db.name,
                            "commerce_expiry_notice"));
        }
    }

    @Test
    void physicalColumnNamesTypesNullabilityAndAutoIdsMatchDictionary() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var lines =
                    new String(
                                    getClass()
                                            .getResourceAsStream("/expected-commerce-columns.tsv")
                                            .readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .lines()
                            .toList();
            assertEquals(627, lines.size());
            for (var line : lines) {
                var parts = line.split("\t");
                var actual =
                        db.jdbc.queryForObject(
                                "SELECT CONCAT(column_type,'|',is_nullable,'|',extra) FROM"
                                        + " information_schema.columns WHERE table_schema=? AND"
                                        + " table_name=? AND column_name=?",
                                String.class,
                                db.name,
                                parts[0],
                                parts[1]);
                assertEquals(
                        parts[2]
                                + "|"
                                + parts[3]
                                + "|"
                                + (parts[1].equals("id") ? "auto_increment" : ""),
                        actual,
                        line);
            }
        }
    }

    @Test
    void separateSourceTaskAndConversionIdentitiesCannotBeReused() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            db.jdbc.execute(Rows.mch_subscription_period("A"));
            assertThrows(
                    org.springframework.dao.DuplicateKeyException.class,
                    () -> copy(db, "mch_subscription_period", java.util.Map.of("period_no", "p2")));
            db.jdbc.update(
                    "UPDATE mch_subscription_period SET fulfillment_task_no='task1' WHERE"
                            + " tenant_no='A'");
            assertThrows(
                    org.springframework.dao.DuplicateKeyException.class,
                    () ->
                            copy(
                                    db,
                                    "mch_subscription_period",
                                    java.util.Map.of("period_no", "p3", "source_ref_no", "ref3")));
            db.jdbc.execute(Rows.mch_subscription_conversion_item("A"));
            assertThrows(
                    org.springframework.dao.DuplicateKeyException.class,
                    () ->
                            copy(
                                    db,
                                    "mch_subscription_conversion_item",
                                    java.util.Map.of("conversion_no", "second")));
            db.jdbc.execute(Rows.commerce_outbox("A"));
            assertThrows(
                    org.springframework.dao.DuplicateKeyException.class,
                    () -> copy(db, "commerce_outbox", java.util.Map.of("event_no", "other")));
        }
    }

    static void copy(MysqlDatabase db, String table, java.util.Map<String, String> override) {
        var columns =
                db.jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema=?"
                            + " AND table_name=? AND column_name<>'id' ORDER BY ordinal_position",
                        String.class,
                        db.name,
                        table);
        var values = new java.util.ArrayList<Object>();
        String selection =
                columns.stream()
                        .map(
                                c -> {
                                    if (override.containsKey(c)) {
                                        values.add(override.get(c));
                                        return "?";
                                    }
                                    return "`" + c + "`";
                                })
                        .collect(java.util.stream.Collectors.joining(","));
        db.jdbc.update(
                "INSERT INTO `"
                        + table
                        + "` ("
                        + columns.stream()
                                .map(c -> "`" + c + "`")
                                .collect(java.util.stream.Collectors.joining(","))
                        + ") SELECT "
                        + selection
                        + " FROM `"
                        + table
                        + "` WHERE tenant_no='A'",
                values.toArray());
    }

    @Test
    void immutableFactsCannotBeSoftDeleted() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            db.jdbc.execute(Rows.mall_quote("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> db.jdbc.update("UPDATE `mall_quote` SET deleted=1 WHERE tenant_no='A'"),
                    "mall_quote");
            db.jdbc.execute(Rows.mall_order("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> db.jdbc.update("UPDATE `mall_order` SET deleted=1 WHERE tenant_no='A'"),
                    "mall_order");
            db.jdbc.execute(Rows.mall_order_item("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mall_order_item` SET deleted=1 WHERE tenant_no='A'"),
                    "mall_order_item");
            db.jdbc.execute(Rows.mall_order_component("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mall_order_component` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mall_order_component");
            db.jdbc.execute(Rows.mall_payment_ref("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mall_payment_ref` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mall_payment_ref");
            db.jdbc.execute(Rows.mall_fulfillment_task("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mall_fulfillment_task` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mall_fulfillment_task");
            db.jdbc.execute(Rows.mall_shipment("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mall_shipment` SET deleted=1 WHERE tenant_no='A'"),
                    "mall_shipment");
            db.jdbc.execute(Rows.mall_refund("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> db.jdbc.update("UPDATE `mall_refund` SET deleted=1 WHERE tenant_no='A'"),
                    "mall_refund");
            db.jdbc.execute(Rows.mall_refund_item("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mall_refund_item` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mall_refund_item");
            db.jdbc.execute(Rows.commerce_event_inbox("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `commerce_event_inbox` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "commerce_event_inbox");
            db.jdbc.execute(Rows.mch_subscription_account("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_account` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_account");
            db.jdbc.execute(Rows.mch_subscription_period("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_period` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_period");
            db.jdbc.execute(Rows.mch_store_end_grant("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_store_end_grant` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_store_end_grant");
            db.jdbc.execute(Rows.mch_subscription_reservation("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_reservation` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_reservation");
            db.jdbc.execute(Rows.mch_subscription_conversion("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_conversion` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_conversion");
            db.jdbc.execute(Rows.mch_subscription_conversion_item("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_conversion_item` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_conversion_item");
            db.jdbc.execute(Rows.mch_subscription_credit_account("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_credit_account` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_credit_account");
            db.jdbc.execute(Rows.mch_subscription_credit_lot("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_credit_lot` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_credit_lot");
            db.jdbc.execute(Rows.mch_subscription_credit_ledger("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_credit_ledger` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_credit_ledger");
            db.jdbc.execute(Rows.commerce_outbox("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `commerce_outbox` SET deleted=1 WHERE tenant_no='A'"),
                    "commerce_outbox");
            db.jdbc.execute(Rows.mch_subscription_migration_map("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_subscription_migration_map` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_subscription_migration_map");
            db.jdbc.execute(Rows.mch_end_schedule_event("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `mch_end_schedule_event` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "mch_end_schedule_event");
            db.jdbc.execute(Rows.commerce_expiry_notice("A"));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.jdbc.update(
                                    "UPDATE `commerce_expiry_notice` SET deleted=1 WHERE"
                                            + " tenant_no='A'"),
                    "commerce_expiry_notice");
        }
    }
}
