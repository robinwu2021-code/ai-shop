package ai.neargo.shop.food.scenario;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试用的门店设备（TDD-餐饮域迁移新库 §2.8 D-5）：按行业库结构建 {@code ind_store_device} 与精简后的
 * {@code fnb_kds_subscription}，登记一台在用的厨显 {@code D}，看工位 {@code ST}。
 */
final class KdsDeviceFixture {
    private KdsDeviceFixture() {}

    static final String DEVICE = "D";

    /** 旧迁移可能已建过旧结构的订阅表，先删掉再按行业库建。 */
    static void create(JdbcTemplate sql) {
        sql.execute("DROP TABLE IF EXISTS fnb_kds_subscription");
        IndustrySchema.create(sql.getDataSource(), "ind_store_device", "fnb_kds_subscription");
        sql.update("INSERT INTO ind_store_device(tenant_no,entity_no,store_no,device_no,device_type,display_name,"
                + "status,generation,deleted,version,created_at,created_by,updated_at,updated_by)"
                + " VALUES('MAIN','E','S',?,'KDS','厨显','ACTIVE',0,0,0,CURRENT_TIMESTAMP,'T',CURRENT_TIMESTAMP,'T')",
                DEVICE);
        sql.update("INSERT INTO fnb_kds_subscription(tenant_no,entity_no,store_no,device_no,stations,version,"
                + "created_at,created_by,updated_at,updated_by)"
                + " VALUES('MAIN','E','S',?,'[\"ST\"]',0,CURRENT_TIMESTAMP,'T',CURRENT_TIMESTAMP,'T')", DEVICE);
    }
}
