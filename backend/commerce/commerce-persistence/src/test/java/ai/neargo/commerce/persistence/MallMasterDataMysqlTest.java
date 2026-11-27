package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.catalog.*;
import org.junit.jupiter.api.Test;

class MallMasterDataMysqlTest {
    @Test
    void typeEditsPreservePublicationVersionAndEndRegistrationUsesMall() {
        try (var mall = new MysqlDatabase(); var foundation = new MysqlDatabase()) {
            CategoryMysqlTest.mallCatalogSchema(mall, foundation);
            var f = new JdbcCatalogFixture(mall, new JdbcCatalogRepository(mall.ds, foundation.ds));
            var create = new BusinessTypeDraft("CAFE", "Cafe", 3, false, 0, "create");
            f.facade.saveBusinessType(f.ops, create);
            assertThrows(CatalogException.class, () -> f.facade.saveBusinessType(f.ops, create));
            assertThrows(CatalogException.class, () -> f.facade.saveBusinessType(f.ops,
                    new BusinessTypeDraft("CAFE", "Cafe", 3, true, 1, "enable before publication")));
            foundation.jdbc.update("UPDATE sys_dict_item SET attrs=JSON_SET(attrs,'$.market','TW') WHERE item_code='RETAIL'");
            String reason = "修".repeat(512);
            f.facade.saveBusinessType(f.ops, new BusinessTypeDraft("RETAIL", "Retail edited", 4, true, 5, reason));
            var edited = foundation.jdbc.queryForMap("""
                    SELECT version,attrs->>'$.plan_catalog_version' catalog, attrs->>'$.market' market,
                           attrs->>'$.change_reason' reason,updated_by FROM sys_dict_item WHERE item_code='RETAIL'
                    """);
            assertEquals(6L, edited.get("version"));
            assertEquals("7", edited.get("catalog"));
            assertEquals("TW", edited.get("market"));
            assertEquals(reason, edited.get("reason"));
            assertEquals("operator-a", edited.get("updated_by"));
            f.end("BASE", EndScope.STORE, true, false);
            assertEquals("GA", mall.jdbc.queryForObject(
                    "SELECT release_status FROM mall_end_entitlement WHERE end_code='BASE'", String.class));
            var overview = f.facade.overview(f.ops);
            assertEquals(1, overview.businessEnds().size());
            assertEquals(7, overview.businessTypes().stream().filter(t -> t.draft().typeCode().equals("RETAIL"))
                    .findFirst().orElseThrow().catalogVersion());
        }
    }
}
