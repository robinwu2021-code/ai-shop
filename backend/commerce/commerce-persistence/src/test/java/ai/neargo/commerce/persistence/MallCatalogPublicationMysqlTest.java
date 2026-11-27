package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MallCatalogPublicationMysqlTest {
    @Test
    void publishesEditedSkuAndPlansAndReadsTheDictionaryVersionInOverview() {
        try (var mall = new MysqlDatabase(); var foundation = new MysqlDatabase()) {
            CategoryMysqlTest.mallCatalogSchema(mall, foundation);
            var f = new JdbcCatalogFixture(mall, new JdbcCatalogRepository(mall.ds, foundation.ds));
            seedEnds(mall);
            f.plans();
            f.product();
            f.facade.saveProduct(f.ops, new ProductDraft("product","subscriptions",ProductKind.BUNDLE,
                    "Updated","Description",List.of("RETAIL"),2,1,"edit"));
            f.facade.saveSku(f.ops, f.sku());
            var draft = f.facade.overview(f.ops).skuVersions().getFirst().draft();
            f.facade.saveSku(f.ops, new SkuDraft(draft.productNo(), draft.skuNo(), draft.skuVersionNo(),
                    "Edited SKU", draft.listPrice(), draft.price(), draft.allowedBusinessTypes(),
                    draft.refundPolicySnapshot(), draft.components(), draft.maxPerOrder(), 1, "edit SKU"));
            // Effective time must be sampled after locking the plans that define this generation.
            f.uow.required(() -> f.repo.overview("tenant-a", () -> {
                try (var contender = mall.ds.getConnection(); var lock = contender.prepareStatement(
                        "SELECT id FROM sys_industry_plan WHERE tenant_no='tenant-a' FOR UPDATE NOWAIT")) {
                    contender.setAutoCommit(false);
                    var blocked = assertThrows(java.sql.SQLException.class, lock::executeQuery);
                    assertEquals(3572, blocked.getErrorCode());
                } catch (java.sql.SQLException failure) {
                    throw new AssertionError(failure);
                }
                return 1L;
            }));
            var command = f.publication(List.of("sku-1"), 7);
            foundation.jdbc.execute("""
                    CREATE TRIGGER block_dictionary BEFORE UPDATE ON sys_dict_item FOR EACH ROW
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dictionary unavailable'
                    """);
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> f.facade.publishIndustry(f.ops, command));
            assertEquals(1, mall.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mall_catalog_change WHERE dictionary_pending", Integer.class));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> f.facade.overview(f.ops));
            foundation.jdbc.execute("DROP TRIGGER block_dictionary");
            mall.jdbc.execute("""
                    CREATE TRIGGER block_ack BEFORE UPDATE ON mall_catalog_change FOR EACH ROW
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='receipt acknowledgement unavailable'
                    """);
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> f.facade.publishIndustry(f.ops, command));
            mall.jdbc.execute("DROP TRIGGER block_ack");
            var restarted = new JdbcCatalogFixture(mall, new JdbcCatalogRepository(mall.ds, foundation.ds));
            var recovered = restarted.facade.overview(restarted.ops);
            assertEquals(8, recovered.businessTypes().getFirst().catalogVersion());
            assertEquals(6, recovered.businessTypes().getFirst().version());
            var result = restarted.facade.publishIndustry(restarted.ops, command);
            assertEquals(result, restarted.facade.publishIndustry(restarted.ops, command));
            assertEquals(CatalogErrorCode.VERSION_CONFLICT, assertThrows(CatalogException.class,
                    () -> restarted.facade.publishIndustry(restarted.ops, restarted.publication(List.of(), 7))).code());
            assertEquals(1, mall.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM mall_catalog_change
                    WHERE change_type='INDUSTRY_PUBLISHED' AND dictionary_pending=0
                    """, Integer.class));
            assertEquals("edit", mall.jdbc.queryForObject("""
                    SELECT detail->>'$.reason' FROM mall_catalog_change WHERE change_type='PRODUCT_SAVED'
                    ORDER BY id DESC LIMIT 1
                    """, String.class));
            var overview = f.facade.overview(f.ops);
            assertEquals(8, result.catalogVersion());
            assertEquals(8, overview.businessTypes().getFirst().catalogVersion());
            assertEquals(8L, foundation.jdbc.queryForObject("""
                    SELECT JSON_EXTRACT(attrs,'$.plan_catalog_version') FROM sys_dict_item WHERE tenant_no='tenant-a'
                    """, Long.class));
            assertEquals("Edited SKU", overview.skuVersions().getFirst().draft().title());
            assertEquals("PUBLISHED", overview.skuVersions().getFirst().status());
            assertEquals("sku-1", overview.skuDefinitions().getFirst().currentVersionNo());
            assertEquals("ON_SALE", overview.products().getFirst().saleStatus());
            assertEquals("Updated", overview.products().getFirst().draft().name());
            assertEquals(List.of("chain-1","free-1","pro-1"), overview.planDefinitions().stream()
                    .map(PlanDefinitionView::currentVersionNo).filter(java.util.Objects::nonNull).sorted().toList());
            // The new current row must advance the definition token even when its draft is younger.
            var oldDefinition = f.planDefinitionTokens("RETAIL").get("RETAIL:PRO");
            f.facade.savePlan(f.ops, f.plan("PRO", "pro-2", List.of("BASE","POS")));
            var replacement = new IndustryPublicationCommand("replace-pro","RETAIL","free-1","pro-2",
                    "chain-1", List.of(),8,f.planTokens("RETAIL"),f.skuTokens("RETAIL"),
                    f.planDefinitionTokens("RETAIL"),f.skuDefinitionTokens("RETAIL"),"replace");
            f.facade.publishIndustry(f.ops,replacement);
            assertEquals(oldDefinition + 1, f.planDefinitionTokens("RETAIL").get("RETAIL:PRO"));
        }
    }
    static void seedEnds(MysqlDatabase mall) {
        for (var code : List.of("BASE", "POS", "KDS")) {
            mall.jdbc.update("""
                    INSERT INTO mall_end_entitlement
                    (tenant_no,created_at,updated_at,version,deleted,end_code,entitlement_code,name,scope,
                     allowed_business_types,release_status,sale_enabled,bootstrap_required,enabled,client_id,
                     route_policy_ref,acceptance_ref)
                    VALUES ('tenant-a',NOW(),NOW(),1,0,?,?,?,'STORE','["RETAIL"]','GA',?,?,1,'client','routes','acceptance')
                    """, code, code, code, !code.equals("BASE"), code.equals("BASE"));
        }
    }
}
