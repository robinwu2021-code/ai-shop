package ai.neargo.shop.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 美业的门店策略头与修订在行业共用表 {@code ind_store_policy}、{@code ind_config_revision}（TDD-美业域迁移新库 B-2）；
 * 宿主测试的库没有行业库，按美业测试资源里的行业库设计 DDL 补建这两张表。
 */
final class BeautyIndustrySharedTables {
    private static final Path DESIGN = Path.of("../../backend-plugins/shop-industry-beauty/beauty-core/src/test/"
            + "resources/db/n_industry/V1__industry_design.sql");
    /** 美业预约在门店交易库的定稿表（TDD-美业域迁移新库 §2.6 第 1～3 项）。 */
    private static final Path TRADE = Path.of("../../backend-plugins/shop-industry-beauty/beauty-core/src/test/"
            + "resources/db/n_trade/V1__trade_design.sql");

    private BeautyIndustrySharedTables() {}

    static void create(JdbcTemplate sql) {
        create(sql, DESIGN, "ind_store_policy", "ind_config_revision");
        create(sql, TRADE, "ord_appointment", "ord_appointment_item", "ord_appointment_revision",
                // 服务定义归商品域（TDD §2.8 P5）：宿主测试里商品句柄仍是主库，按交易库设计 DDL 补这两张
                "prd_service_definition", "prd_fulfillment_requirement");
    }

    private static void create(JdbcTemplate sql, Path design, String... tables) {
        String text;
        try {
            text = Files.readString(design, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + design.toAbsolutePath(), ex);
        }
        for (String table : tables) {
            int start = text.indexOf("CREATE TABLE `" + table + "`");
            int end = text.indexOf(";\n", start);
            if (start < 0 || end < 0) throw new IllegalStateException("No DDL for " + table + " in " + design);
            sql.execute(text.substring(start, end).replaceFirst("CREATE TABLE", "CREATE TABLE IF NOT EXISTS"));
        }
    }
}
