package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.core.spi.*;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

public final class JdbcSubscriptionAccountRepository implements SubscriptionAccountRepository {
    private final DataSource ds;
    private final JdbcTemplate jdbc;

    public JdbcSubscriptionAccountRepository(DataSource ds) {
        this.ds = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long readEntitlementVersion(StoreRef s) {
        JdbcWriteGuard.tenant(s.tenantNo());
        return jdbc.queryForObject(
                "SELECT entitlement_version FROM mch_subscription_account WHERE tenant_no=? AND"
                    + " store_no=? FOR SHARE",
                Long.class,
                s.tenantNo(),
                s.storeNo());
    }

    public AccountSnapshot lockSubscriptionAccount(StoreRef s) {
        JdbcWriteGuard.require(ds, s.tenantNo());
        return jdbc.queryForObject(
                "SELECT"
                    + " entity_no,business_type_code,entitlement_version,pending_change_order_no,version"
                    + " FROM mch_subscription_account WHERE tenant_no=? AND store_no=? FOR UPDATE",
                (rs, n) ->
                        new AccountSnapshot(
                                s,
                                rs.getString(1),
                                rs.getString(2),
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getLong(5)),
                s.tenantNo(),
                s.storeNo());
    }
}
