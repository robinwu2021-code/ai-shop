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

    private BeautyIndustrySharedTables() {}

    static void create(JdbcTemplate sql) {
        String text;
        try {
            text = Files.readString(DESIGN, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + DESIGN.toAbsolutePath(), ex);
        }
        for (String table : new String[] {"ind_store_policy", "ind_config_revision"}) {
            int start = text.indexOf("CREATE TABLE `" + table + "`");
            int end = text.indexOf(";\n", start);
            if (start < 0 || end < 0) throw new IllegalStateException("No DDL for " + table + " in " + DESIGN);
            sql.execute(text.substring(start, end).replaceFirst("CREATE TABLE", "CREATE TABLE IF NOT EXISTS"));
        }
    }
}
