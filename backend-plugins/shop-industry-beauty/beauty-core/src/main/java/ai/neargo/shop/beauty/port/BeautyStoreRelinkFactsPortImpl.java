package ai.neargo.shop.beauty.port;

import ai.neargo.shop.spi.user.StoreRelinkIndustryFactsPort;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** All original rows count, including cancelled and archived business history. */
@Component
public final class BeautyStoreRelinkFactsPortImpl implements StoreRelinkIndustryFactsPort {
    /** 每张表按它所在的库查（B-5d）：预约在交易库、预约操作还在主库、工单与接待操作在行业库。 */
    private final java.util.Map<String, JdbcTemplate> byTable;
    private ai.neargo.shop.spi.user.StoreOwnershipWritePort ownershipWrites;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOwnershipWrites(ai.neargo.shop.spi.user.StoreOwnershipWritePort ownershipWrites) {
        this.ownershipWrites = ownershipWrites;
    }
    // Original caller/sourceOwner contracts do not map to frozen commerce business-type codes.
    @Override public java.util.Set<String> businessTypes() { return java.util.Set.of(); }
    /** 全部表在同一个库（测试）。 */
    public BeautyStoreRelinkFactsPortImpl(JdbcTemplate jdbc) { this(jdbc, jdbc, jdbc); }
    BeautyStoreRelinkFactsPortImpl(JdbcTemplate main, JdbcTemplate trade, JdbcTemplate industry) {
        this.byTable = java.util.Map.of("bty_appointment", trade, "bty_appointment_operation", main,
                "bty_appointment_preparation", trade, "bty_work_order", industry, "bty_reception_operation", industry);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public BeautyStoreRelinkFactsPortImpl(
            @org.springframework.beans.factory.annotation.Qualifier("dataSource") javax.sql.DataSource main,
            @org.springframework.beans.factory.annotation.Qualifier(
                    ai.neargo.shop.common.config.ModuleDataSources.TRADE_DATA_SOURCE) javax.sql.DataSource trade,
            @org.springframework.beans.factory.annotation.Qualifier(
                    ai.neargo.shop.common.config.ModuleDataSources.INDUSTRY_DATA_SOURCE) javax.sql.DataSource industry) {
        this(new JdbcTemplate(main), new JdbcTemplate(trade), new JdbcTemplate(industry));
    }
    private static final List<String> TABLES=List.of("bty_appointment","bty_appointment_operation","bty_appointment_preparation","bty_work_order","bty_reception_operation");
    @Override public Facts inspect(String tenant, String entity, String store) {
        boolean history=false;
        for (String table:TABLES) {
            boolean found=Boolean.TRUE.equals(byTable.get(table).queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE tenant_no=? AND (store_no=? OR (entity_no=? AND (store_no IS NULL OR store_no=''))))",
                    Boolean.class,tenant,store,entity));
            history |= found;
        }
        return new Facts(ownershipWrites != null,history);
    }
}
