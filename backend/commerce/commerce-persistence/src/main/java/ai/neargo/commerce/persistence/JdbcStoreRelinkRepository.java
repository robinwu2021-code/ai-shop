package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.subscription.StoreRelinkFacade;
import ai.neargo.commerce.core.spi.StoreRelinkRepository;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

/** Only commerce-owned tables. Missing schema is a failure, never clean evidence. */
public final class JdbcStoreRelinkRepository implements StoreRelinkRepository {
    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final String readLock;
    public JdbcStoreRelinkRepository(DataSource ds) {
        this.ds=ds; this.jdbc=new JdbcTemplate(ds);
        this.readLock=jdbc.execute((ConnectionCallback<String>) connection ->
                "H2".equals(connection.getMetaData().getDatabaseProductName()) ? " FOR UPDATE" : " FOR SHARE");
    }
    private static final List<String> HISTORY_TABLES = List.of(
            "mall_order", "mall_refund", "mch_subscription_reservation", "mch_subscription_conversion",
            "mch_subscription_credit_account", "mch_subscription_credit_lot", "mch_subscription_credit_ledger");

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql + readLock, args).isEmpty();
    }

    @Override public StoreRelinkFacade.Facts inspect(StoreRef store) {
        boolean history=false;
        // Current reads cannot reuse a host caller's older RR snapshot.
        // Read every source even after a hit: missing schema must not report complete.
        for (String table : HISTORY_TABLES) {
            history |= exists("SELECT 1 FROM " + table + " WHERE tenant_no=? AND store_no=? LIMIT 1",
                    store.tenantNo(),store.storeNo());
        }
        history |= exists("""
                SELECT 1 FROM mch_subscription_period WHERE tenant_no=? AND store_no=?
                  AND (source_type<>'FREE' OR tier_code IS NULL OR tier_code<>'FREE'
                    OR granted_duration_ms IS NOT NULL OR eligible_basis_minor<>0
                    OR cash_basis_minor<>0 OR credit_basis_minor<>0) LIMIT 1
                """,store.tenantNo(),store.storeNo());
        history |= exists("""
                SELECT 1 FROM mch_subscription_account WHERE tenant_no=? AND store_no=?
                  AND pending_change_order_no IS NOT NULL LIMIT 1
                """,store.tenantNo(),store.storeNo());
        boolean account=exists("SELECT 1 FROM mch_subscription_account WHERE tenant_no=? AND store_no=? LIMIT 1",
                store.tenantNo(),store.storeNo());
        boolean periods=exists("SELECT 1 FROM mch_subscription_period WHERE tenant_no=? AND store_no=? LIMIT 1",
                store.tenantNo(),store.storeNo());
        if (!account && periods) history=true;
        return new StoreRelinkFacade.Facts(!account || periods,history,account);
    }

    @Override public void requireOuterTransaction(StoreRef store) { JdbcWriteGuard.require(ds,store.tenantNo()); }

    @Override public void lockAndRelink(StoreRelinkFacade.Command command) {
        var store=command.store();
        requireOuterTransaction(store);
        var account=jdbc.queryForList("SELECT entity_no FROM mch_subscription_account WHERE tenant_no=? AND store_no=? FOR UPDATE",
                String.class,store.tenantNo(),store.storeNo());
        var facts=inspect(store);
        if (!facts.complete() || facts.hasPaidHistory()) throw new IllegalStateException("STORE_RELINK_PAID_HISTORY");
        if (account.isEmpty()) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mch_subscription_period WHERE tenant_no=? AND store_no=?)",
                    Boolean.class,store.tenantNo(),store.storeNo()))) throw new IllegalStateException("STORE_RELINK_ORPHAN_PERIOD");
            return; // Legacy store with no commerce account and no owned history.
        }
        String current=account.getFirst();
        if (!current.equals(command.expectedEntityNo())) throw new IllegalStateException("STORE_RELINK_ENTITY_CONFLICT");
        var periodEntities=jdbc.queryForList("SELECT entity_no FROM mch_subscription_period WHERE tenant_no=? AND store_no=? FOR UPDATE",
                String.class,store.tenantNo(),store.storeNo());
        if (periodEntities.stream().anyMatch(entity -> !entity.equals(command.expectedEntityNo())))
            throw new IllegalStateException("STORE_RELINK_PERIOD_ENTITY_CONFLICT");
        int changed=jdbc.update("""
                UPDATE mch_subscription_account SET entity_no=?,version=version+1,
                  entitlement_version=entitlement_version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?
                WHERE tenant_no=? AND store_no=? AND entity_no=?
                """,command.targetEntityNo(),command.changeNo(),store.tenantNo(),store.storeNo(),command.expectedEntityNo());
        if (changed!=1) throw new IllegalStateException("STORE_RELINK_ENTITY_CONFLICT");
        jdbc.update("""
                UPDATE mch_subscription_period SET entity_no=?,version=version+1,
                  updated_at=UTC_TIMESTAMP(3),updated_by=?
                WHERE tenant_no=? AND store_no=? AND entity_no=? AND source_type='FREE' AND tier_code='FREE'
                """,command.targetEntityNo(),command.changeNo(),store.tenantNo(),store.storeNo(),command.expectedEntityNo());
    }
}
