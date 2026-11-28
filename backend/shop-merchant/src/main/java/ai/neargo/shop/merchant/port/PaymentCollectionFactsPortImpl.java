package ai.neargo.shop.merchant.port;

import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import ai.neargo.shop.spi.user.PaymentCollectionFactsPort;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 收款核验用的门店、主体、账号事实：平台基础库，经平台句柄读。 */
@Component
public class PaymentCollectionFactsPortImpl implements PaymentCollectionFactsPort {

    /** 账号在用 */
    static final String ACCOUNT_ACTIVE = "ACTIVE";

    private static final RowMapper<StoreFact> STORE = (rs, i) -> new StoreFact(rs.getLong("id"),
            rs.getString("tenant_no"), rs.getString("store_no"), rs.getString("entity_no"), rs.getString("market"));

    private final JdbcTemplate jdbc;

    public PaymentCollectionFactsPortImpl(
            @Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoreFact> storeByNo(String storeNo) {
        return jdbc.query("""
                SELECT s.id, s.tenant_no, s.store_no, s.entity_no, e.market
                FROM mch_store s JOIN mch_entity e ON e.entity_no=s.entity_no AND e.deleted=0
                WHERE s.store_no=? AND s.deleted=0
                """, STORE, storeNo).stream().findFirst();
    }

    @Override
    public Optional<StoreFact> storeById(String tenantNo, long storeId) {
        return jdbc.query("""
                SELECT s.id, s.tenant_no, s.store_no, s.entity_no, e.market
                FROM mch_store s JOIN mch_entity e
                  ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no AND e.deleted=0
                WHERE s.tenant_no=? AND s.id=? AND s.deleted=0
                """, STORE, tenantNo, storeId).stream().findFirst();
    }

    @Override
    public List<Account> activeAccounts(String entityNo) {
        return jdbc.query("SELECT mch_account_no,user_no FROM mch_account WHERE entity_no=? AND status=? AND deleted=0",
                (rs, i) -> new Account(rs.getString(1), rs.getString(2)), entityNo, ACCOUNT_ACTIVE);
    }
}
