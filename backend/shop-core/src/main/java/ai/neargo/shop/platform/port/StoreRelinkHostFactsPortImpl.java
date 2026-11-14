package ai.neargo.shop.platform.port;

import ai.neargo.shop.spi.user.StoreRelinkHostFactsPort;
import java.util.Map;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Host history evidence only. It neither proves financial cleanliness nor fences concurrent writes. */
@Component
public class StoreRelinkHostFactsPortImpl implements StoreRelinkHostFactsPort {
    private final NamedParameterJdbcTemplate jdbc;

    public StoreRelinkHostFactsPortImpl(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    // History includes cancelled/voided and archived rows. A source-entity row without
    // an attributable store must not be treated as evidence that this store is clean.
    private static final String HISTORY = """
            SELECT CASE WHEN EXISTS (
              SELECT 1 FROM ord_order o
              WHERE o.tenant_no=:tenant AND
                (o.store_no=:store OR (o.entity_no=:entity AND (o.store_no IS NULL OR o.store_no='')))
              UNION ALL
              SELECT 1 FROM ord_sub_order s
              WHERE s.tenant_no=:tenant AND
                (s.store_no=:store OR (s.entity_no=:entity AND (s.store_no IS NULL OR s.store_no='')))
              UNION ALL
              SELECT 1 FROM ord_offline_record r
              WHERE r.tenant_no=:tenant AND
                (r.store_no=:store OR (r.entity_no=:entity AND (r.store_no IS NULL OR r.store_no='')))
              UNION ALL
              SELECT 1 FROM ord_after_sale a
              WHERE a.tenant_no=:tenant AND (
                EXISTS (SELECT 1 FROM ord_sub_order s
                  WHERE s.tenant_no=a.tenant_no AND s.sub_order_no=a.sub_order_no AND
                    (s.store_no=:store OR (s.entity_no=:entity AND (s.store_no IS NULL OR s.store_no=''))))
                OR EXISTS (SELECT 1 FROM ord_order o
                  WHERE o.tenant_no=a.tenant_no AND o.order_no=a.order_no AND
                    (o.store_no=:store OR (o.entity_no=:entity AND (o.store_no IS NULL OR o.store_no=''))))
                OR (a.entity_no=:entity
                  AND NOT EXISTS (SELECT 1 FROM ord_sub_order s
                    WHERE s.tenant_no=a.tenant_no AND s.sub_order_no=a.sub_order_no)
                  AND NOT EXISTS (SELECT 1 FROM ord_order o
                    WHERE o.tenant_no=a.tenant_no AND o.order_no=a.order_no))
              )
            ) THEN TRUE ELSE FALSE END
            """;

    private static final String PENDING_APPLY = """
            SELECT CASE WHEN EXISTS (
              SELECT 1 FROM mch_entity_apply
              WHERE tenant_no=:tenant AND entity_no=:entity AND deleted=0
                AND status IN ('PENDING','REVIEWING')
            ) THEN TRUE ELSE FALSE END
            """;

    private static final List<String> STORE_HISTORY = List.of(
            "ord_checkout_operation", "ord_checkout_confirmation", "ord_ent_service_order",
            "ord_external_certificate_use", "pmt_apply", "ord_industry_order_source", "ord_channel_import", "ord_channel_admission_guard");
    private static final List<String> ENTITY_HISTORY = List.of(
            "pmt_coupon", "pmt_user_coupon", "pmt_coupon_issue", "mkt_coupon",
            "pmt_pay_coupon_projection", "pmt_migration_decision");

    @Override
    @Transactional(readOnly = true)
    public Facts inspect(String tenantNo, String entityNo, String storeNo) {
        var scope = Map.of("tenant", tenantNo, "entity", entityNo, "store", storeNo);
        boolean trades = Boolean.TRUE.equals(jdbc.queryForObject(HISTORY, scope, Boolean.class));
        for (String table : STORE_HISTORY) {
            trades |= Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                    + " WHERE tenant_no=:tenant AND (store_no=:store OR (entity_no=:entity AND (store_no IS NULL OR store_no=''))))",
                    scope, Boolean.class));
        }
        // These sources are entity-wide. They have no safe per-store attribution to migrate.
        for (String table : ENTITY_HISTORY) {
            trades |= Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                    + " WHERE tenant_no=:tenant AND entity_no=:entity)", scope, Boolean.class));
        }
        // Product data is not moved by N1. Even drafts, deleted goods and zero stock
        // retain the old entity owner; a sibling's entity-wide catalog also blocks retirement.
        for (String table : List.of("prd_goods", "prd_store_stock", "prd_store_goods", "prd_store_price")) {
            trades |= Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                    + " WHERE tenant_no=:tenant AND (entity_no=:entity OR store_no=:store))", scope, Boolean.class));
        }
        // Replenishment originals, connections, receipts and evidence keep their frozen pool/source claim;
        // an empty catalog must not hide them. The writer fence is entity-wide.
        for (String table : List.of("prd_stock_replenishment", "prd_stock_replenishment_connection",
                "prd_stock_replenishment_command", "prd_stock_replenishment_movement",
                "prd_stock_replenishment_manual", "prd_stock_replenishment_return")) {
            trades |= Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                    + " WHERE tenant_no=:tenant AND (entity_no=:entity OR store_no=:store))", scope, Boolean.class));
        }
        for (String table : List.of("prd_sku", "prd_goods_draft", "prd_stock_writer")) {
            trades |= Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                    + " WHERE tenant_no=:tenant AND entity_no=:entity)", scope, Boolean.class));
        }
        // Existing core points journals have globally unique store keys, not tenant columns;
        // their current contract is MAIN-only. No PAY points ledger is read here.
        for (String table : List.of("ord_points_binding", "ord_points_source_fact")) {
            trades |= Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                    + " WHERE :tenant='MAIN' AND store_no=:store)", scope, Boolean.class));
        }
        boolean pending = Boolean.TRUE.equals(jdbc.queryForObject(PENDING_APPLY, scope, Boolean.class));
        return new Facts(trades, pending);
    }
}
