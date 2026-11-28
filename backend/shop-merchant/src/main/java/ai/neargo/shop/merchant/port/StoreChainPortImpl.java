package ai.neargo.shop.merchant.port;

import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import ai.neargo.shop.spi.org.StoreChainPort;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 门店所属连锁：读生效中的品牌绑定与品牌（平台基础库）。 */
@Component
public class StoreChainPortImpl implements StoreChainPort {

    /** 绑定生效、品牌在用、门店在营共用的状态值 */
    static final String ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbc;

    public StoreChainPortImpl(@Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Chain> chainOf(String tenantNo, String storeNo) {
        return jdbc.query("""
                SELECT b.brand_no, r.management_no, r.legal_entity_no, s.entity_no, r.scope_revision
                  FROM mch_brand_store_binding b
                  JOIN mch_brand r ON r.tenant_no = b.tenant_no AND r.brand_no = b.brand_no
                                  AND r.status = ? AND r.deleted = 0
                  JOIN mch_store s ON s.tenant_no = b.tenant_no AND s.store_no = b.store_no AND s.deleted = 0
                 WHERE b.tenant_no = ? AND b.store_no = ? AND b.status = ? AND b.deleted = 0
                """, (rs, i) -> new Chain(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5)),
                ACTIVE, tenantNo, storeNo, ACTIVE).stream().findFirst();
    }
}
