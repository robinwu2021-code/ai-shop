package ai.neargo.shop.food.port;

import ai.neargo.shop.spi.user.StoreRelinkIndustryFactsPort;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** All original rows count, including cancelled and archived business history. */
@Component
public final class FoodStoreRelinkFactsPortImpl implements StoreRelinkIndustryFactsPort {
    private final JdbcTemplate jdbc;
    private ai.neargo.shop.spi.user.StoreOwnershipWritePort ownershipWrites;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOwnershipWrites(ai.neargo.shop.spi.user.StoreOwnershipWritePort ownershipWrites) {
        this.ownershipWrites = ownershipWrites;
    }
    // Original caller/sourceOwner contracts do not map to frozen commerce business-type codes.
    @Override public java.util.Set<String> businessTypes() { return java.util.Set.of(); }
    public FoodStoreRelinkFactsPortImpl(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @org.springframework.beans.factory.annotation.Autowired
    public FoodStoreRelinkFactsPortImpl(@org.springframework.beans.factory.annotation.Qualifier("foodSqlSessionTemplate")
            org.mybatis.spring.SqlSessionTemplate template) {
        this(new JdbcTemplate(template.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource()));
    }
    private static final List<String> TABLES=List.of("fnb_order_batch","fnb_channel_order_source","fnb_channel_import_guard");
    @Override public Facts inspect(String tenant, String entity, String store) {
        boolean history=false;
        for (String table:TABLES) {
            boolean found=Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE tenant_no=? AND (store_no=? OR (entity_no=? AND (store_no IS NULL OR store_no=''))))",
                    Boolean.class,tenant,store,entity));
            history |= found;
        }
        return new Facts(ownershipWrites != null,history);
    }
}
