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
    void listsCategoriesFromTheMallTableAlone() {
        try (var db = new MysqlDatabase()) {
            db.jdbc.execute("""
                    CREATE TABLE mall_category (
                        id BIGINT PRIMARY KEY, tenant_no VARCHAR(32), deleted TINYINT,
                        category_code VARCHAR(32), parent_code VARCHAR(32), names JSON,
                        icon VARCHAR(512), sort_order INT, status VARCHAR(16),
                        version BIGINT, `level` INT)
                    """);
            db.jdbc.update("""
                    INSERT INTO mall_category VALUES
                    (1,'tenant-a',0,'active',NULL,'{"en":"A"}',NULL,2,'ACTIVE',3,1),
                    (2,'tenant-a',0,'disabled',NULL,'{"en":"B"}',NULL,1,'DISABLED',4,1),
                    (3,'tenant-b',0,'foreign',NULL,'{}',NULL,0,'ACTIVE',1,1),
                    (4,'tenant-a',1,'deleted',NULL,'{}',NULL,0,'ACTIVE',1,1)
                    """);
            var f = new JdbcCatalogFixture(db);
            var list = f.facade.categories(f.ops);
            assertEquals(List.of("disabled", "active"), list.stream().map(CategoryView::categoryCode).toList());
            assertEquals(List.of("DISABLED", "ENABLED"), list.stream().map(CategoryView::status).toList());
            f.capabilities.clear();
            assertEquals(CatalogErrorCode.FORBIDDEN,
                    assertThrows(CatalogException.class, () -> f.facade.categories(f.ops)).code());
        }
    }

    @Test
    void savesAndReadsOverviewAcrossTheMallAndDictionaryDatabases() {
        try (var mall = new MysqlDatabase(); var foundation = new MysqlDatabase()) {
            mallCatalogSchema(mall, foundation);
            mall.jdbc.execute("""
                    INSERT INTO sys_industry_plan (id,created_at,updated_at,`rank`,sort,tenant_no,deleted,version,plan_no,version_no,revision,business_type_code,
                        tier_code,sale_status,status,effective_from,effective_to,display_name,summary,policy_version,
                        change_note,published_at,published_by) VALUES
                    (1,NOW(),NOW(),2,2,'tenant-a',0,3,'pro','old',1,'RETAIL','PRO','OFF_SALE','ACTIVE',0,1,'old',NULL,'p',NULL,0,'ops'),
                    (2,NOW(),NOW(),2,2,'tenant-a',0,4,'pro','current',2,'RETAIL','PRO','ON_SALE','ACTIVE',1,NULL,'current',NULL,'p',NULL,1,'ops'),
                    (3,NOW(),NOW(),2,2,'tenant-a',0,5,'pro','next',3,'RETAIL','PRO','ON_SALE','ACTIVE',9999999999999,NULL,'next',NULL,'p',NULL,2,'ops')
                    """);
            mall.jdbc.execute(Rows.sys_business_end("tenant-a").replace("sys_business_end", "mall_end_entitlement"));
            mall.jdbc.update("""
                    UPDATE mall_end_entitlement SET scope='ENTITY',release_status='GA',allowed_business_types='["RETAIL"]'
                    """);
            mall.jdbc.execute(product("tenant-a", "pos", "hw"));
            mall.jdbc.update("UPDATE mall_product SET product_type='SERVICE'");
            mall.jdbc.execute(Rows.mall_sku("tenant-a"));
            mall.jdbc.update("UPDATE mall_sku SET product_no='pos'");
            mall.jdbc.execute(Rows.mall_sku_version("tenant-a"));
            mall.jdbc.update("UPDATE mall_sku_version SET allowed_business_types='[]',currency='CNY'");
            mall.jdbc.execute(Rows.mall_sku_component("tenant-a"));
            mall.jdbc.update("UPDATE mall_sku_component SET component_type='ENTITLEMENT'");
            mall.jdbc.execute(Rows.sys_plan_version_end("tenant-a"));
            mall.jdbc.update("UPDATE sys_plan_version_end SET plan_version_no='current',entitlement_code='POS'");
            var fixture = new JdbcCatalogFixture(mall, new JdbcCatalogRepository(mall.ds, foundation.ds));
            var create = new CategoryDraft("hw", null, Map.of("en","Hardware"), null, 1, true, 0, "create");
            fixture.facade.saveCategory(fixture.ops, create);
            assertEquals("ACTIVE", mall.jdbc.queryForObject("SELECT status FROM mall_category", String.class));
            var overview = fixture.facade.overview(fixture.ops);
            assertEquals(1, overview.businessTypes().size());
            assertEquals(7, overview.businessTypes().getFirst().catalogVersion());
            assertEquals(5, overview.businessTypes().getFirst().version());
            assertEquals("current", overview.planDefinitions().stream().filter(p -> "pro".equals(p.planNo()))
                    .findFirst().orElseThrow().currentVersionNo());
            assertEquals(3, overview.planVersions().size());
            assertEquals("ENABLED", overview.categories().getFirst().status());
            assertEquals(EndScope.ENTITY, overview.businessEnds().getFirst().draft().scope());
            assertEquals(ReleaseStatus.VERIFIED, overview.businessEnds().getFirst().draft().releaseStatus());
            assertEquals(ProductKind.SERVICE, overview.products().getFirst().draft().kind());
            assertEquals(List.of("POS"), overview.planVersions().stream().map(PlanVersionView::draft)
                    .filter(plan -> plan.planVersionNo().equals("current")).findFirst().orElseThrow().entitlementCodes());
            assertEquals(ai.neargo.commerce.api.common.ComponentKind.END_PACKAGE,
                    overview.skuVersions().getFirst().draft().components().getFirst().kind());
            var disable = new CategoryDraft("hw", null, Map.of("en","Hardware"), null, 1, false, 1, "disable");

            assertEquals(CatalogErrorCode.CATEGORY_IN_USE,
                    assertThrows(CatalogException.class, () -> fixture.facade.saveCategory(fixture.ops, disable)).code());
            mall.jdbc.update("UPDATE mall_product SET sale_status='OFF_SALE'");
            fixture.facade.saveCategory(fixture.ops, disable);
            assertEquals(Map.of("created_by", "operator-a", "updated_by", "operator-a", "version", 2L),
                    mall.jdbc.queryForMap("SELECT created_by,updated_by,version FROM mall_category"));
            assertEquals("DISABLED", mall.jdbc.queryForObject("SELECT status FROM mall_category", String.class));
        }
    }

    static void mallCatalogSchema(MysqlDatabase mall, MysqlDatabase foundation) {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/mall/catalog/V1__mall_catalog.sql"))
                .execute(mall.ds);
        foundation.jdbc.execute("""
                CREATE TABLE sys_dict_item (
                    tenant_no varchar(32), dict_type varchar(32), item_level varchar(16),
                    item_code varchar(64), name varchar(64), sort_no int, status varchar(16),
                    version bigint, attrs json, deleted tinyint, created_at datetime(3), updated_at datetime(3),
                    created_by varchar(64), updated_by varchar(64), source varchar(32), remark varchar(255),
                    unique(tenant_no,dict_type,item_code))
                """);
        foundation.jdbc.update("""
                INSERT INTO sys_dict_item (tenant_no,dict_type,item_level,item_code,name,sort_no,status,version,attrs,deleted) VALUES
                ('tenant-a','BUSINESS_TYPE','TYPE','RETAIL','Retail',1,'ENABLED',5,
                 '{"plan_catalog_version":7}',0),
                ('tenant-b','BUSINESS_TYPE','TYPE','OTHER','Other',2,'ENABLED',1,NULL,0)
                """);
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
