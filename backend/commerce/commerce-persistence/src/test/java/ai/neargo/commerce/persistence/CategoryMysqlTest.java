package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CategoryMysqlTest {
    static String product(String tenant, String no, String category) {
        return "INSERT INTO `mall_product` (`tenant_no`,`created_at`,`updated_at`,`version`,`deleted`,"
                + "`product_no`,`category_code`,`product_type`,`name`,`allowed_business_types`,"
                + "`sale_status`,`sort`) VALUES ('" + tenant + "','2026-11-03 00:00:00','2026-11-03 00:00:00',1,0,'"
                + no + "','" + category + "','BUNDLE','n','[\"RETAIL\"]','ON_SALE',0)";
    }

    @Test
    void v11SeedsLevelOneCategoriesFromExistingProducts() {
        try (var db = new MysqlDatabase()) {
            db.migrate("10");
            db.jdbc.execute(product("A", "p1", "hardware"));
            db.jdbc.execute(product("A", "p2", "hardware"));
            db.jdbc.execute(product("A", "p3", "plans"));
            db.jdbc.execute(product("B", "p1", "hardware"));
            db.migrate();
            var rows =
                    db.jdbc.queryForList(
                            "SELECT tenant_no, category_code, parent_code, level, status, version,"
                                    + " JSON_UNQUOTE(JSON_EXTRACT(names,'$.\"zh-TW\"')) tw"
                                    + " FROM mall_category ORDER BY tenant_no, category_code");
            assertEquals(3, rows.size());
            assertEquals(
                    List.of("A:hardware", "A:plans", "B:hardware"),
                    rows.stream().map(r -> r.get("tenant_no") + ":" + r.get("category_code")).toList());
            for (var r : rows) {
                assertNull(r.get("parent_code"));
                assertEquals(1, ((Number) r.get("level")).intValue());
                assertEquals("ENABLED", r.get("status"));
                assertEquals(r.get("category_code"), r.get("tw"));
            }
        }
    }

    @Test
    void treeRoundTripsWithLevelsNamesAndVersions() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.facade.saveCategory(
                    f.ops,
                    new CategoryDraft(
                            "hw", null, Map.of("zh-TW", "硬體", "en", "Hardware"), "https://x/i.png",
                            2, true, 0, "create"));
            f.facade.saveCategory(f.ops, new CategoryDraft("pos", "hw", Map.of("en", "POS"), null, 1, true, 0, "create"));
            f.facade.saveCategory(f.ops, new CategoryDraft("printer", "pos", Map.of("en", "Printer"), null, 1, true, 0, "create"));
            // move pos to top level: printer is re-levelled in the same write
            f.facade.saveCategory(f.ops, new CategoryDraft("pos", null, Map.of("en", "POS"), null, 1, true, 1, "move"));
            var list = f.facade.categories(f.ops);
            assertEquals(List.of("pos", "hw", "printer"), list.stream().map(CategoryView::categoryCode).toList());
            assertEquals(List.of(1, 1, 2), list.stream().map(CategoryView::level).toList());
            var hw = list.get(1);
            assertEquals(Map.of("en", "Hardware", "zh-TW", "硬體"), hw.names());
            assertEquals("https://x/i.png", hw.icon());
            assertEquals(2, list.get(2).version());
            assertEquals(
                    2,
                    db.jdbc.queryForObject(
                            "SELECT level FROM mall_category WHERE category_code='printer'", Integer.class));
            f.facade.saveProduct(
                    f.ops,
                    new ProductDraft("p", "printer", ProductKind.HARDWARE, "印表機", "", List.of("RETAIL"), 0, 0, "create"));
            assertThrows(
                    CatalogException.class,
                    () -> f.facade.saveCategory(f.ops, new CategoryDraft("pos", null, Map.of("en", "POS"), null, 1, true, 1, "stale")));
        }
    }
}
