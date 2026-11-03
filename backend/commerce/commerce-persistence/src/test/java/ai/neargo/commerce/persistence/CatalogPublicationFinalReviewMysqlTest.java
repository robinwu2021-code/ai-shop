package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.*;

import java.util.*;

class CatalogPublicationFinalReviewMysqlTest {
    private MysqlDatabase db;
    private JdbcCatalogFixture f;

    @BeforeEach
    void setup() {
        db = new MysqlDatabase();
        db.migrate();
        f = new JdbcCatalogFixture(db);
        f.setup();
        f.plans();
        f.product();
    }

    @AfterEach
    void cleanup() {
        if (db != null) db.close();
    }

    private int auditCount() {
        return db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class);
    }

    private void setSkuStatus(String status) {
        assertEquals(
                1,
                db.jdbc.update(
                        "UPDATE mall_sku_version SET status=? WHERE tenant_no=? AND"
                                + " sku_version_no=?",
                        status,
                        "tenant-a",
                        "sku-1"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DRAFT", "HISTORICAL"})
    void unselectedFreeVersionDoesNotGovernCurrentPaidEndPackage(String mode) {
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        f.facade.savePlan(f.ops, f.plan("FREE", "free-other", List.of("BASE", "POS")));
        long catalogVersion = 1;
        if (mode.equals("HISTORICAL")) {
            f.facade.publishIndustry(f.ops, selectedFree("free-other", List.of(), 1));
            f.facade.publishIndustry(f.ops, f.publication(List.of(), 2));
            catalogVersion = 3;
        }
        var saved = f.facade.saveSku(f.ops, endPackage());
        f.facade.publishSku(
                f.ops,
                new SkuPublicationCommand(
                        null,
                        saved.draftVersionNo(),
                        saved.version(),
                        1,
                        "independent current paid POS"));
        assertEquals("sku-1", f.facade.catalog(f.ops, "RETAIL").skus().getFirst().skuVersionNo());
        // Reusing the eligible current selection must also ignore unselected FREE history/drafts.
        f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), catalogVersion));
        assertEquals("sku-1", f.facade.catalog(f.ops, "RETAIL").skus().getFirst().skuVersionNo());
    }

    @Test
    void explicitlySelectedConflictingFreeRejectsPackageAtomically() {
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        f.facade.saveSku(f.ops, endPackage());
        f.facade.savePlan(f.ops, f.plan("FREE", "free-other", List.of("BASE", "POS")));
        var command = selectedFree("free-other", List.of("sku-1"), 1);
        var before = f.facade.catalog(f.ops, "RETAIL");
        int audits = auditCount();
        assertEquals(
                CatalogErrorCode.INVALID_ARGUMENT,
                assertThrows(CatalogException.class, () -> f.facade.publishIndustry(f.ops, command))
                        .code());
        assertEquals(before, f.facade.catalog(f.ops, "RETAIL"));
        assertEquals(audits, auditCount());
    }

    @Test
    void actualCurrentFreeStillForbidsFinitePaidEndDraft() {
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        f.facade.savePlan(f.ops, f.plan("FREE", "free-other", List.of("BASE", "POS")));
        f.facade.publishIndustry(f.ops, selectedFree("free-other", List.of(), 1));
        var before = f.facade.catalog(f.ops, "RETAIL");
        int audits = auditCount();
        assertEquals(
                CatalogErrorCode.INVALID_ARGUMENT,
                assertThrows(CatalogException.class, () -> f.facade.saveSku(f.ops, endPackage()))
                        .code());
        assertEquals(before, f.facade.catalog(f.ops, "RETAIL"));
        assertEquals(audits, auditCount());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ARCHIVED", "UNKNOWN"})
    void invalidCandidateLifecycleRejectsBeforeCatalogPointersOrAuditChange(String status) {
        f.facade.saveSku(f.ops, f.sku());
        setSkuStatus(status);
        var command = f.publication(List.of("sku-1"), 0);
        var before = f.facade.catalog(f.ops, "RETAIL");
        int audits = auditCount();
        assertEquals(
                CatalogErrorCode.INVALID_ARGUMENT,
                assertThrows(CatalogException.class, () -> f.facade.publishIndustry(f.ops, command))
                        .code());
        assertEquals(before, f.facade.catalog(f.ops, "RETAIL"));
        assertEquals(audits, auditCount());
        assertTrue(before.plans().isEmpty());
        assertNull(before.skuDefinitions().getFirst().currentVersionNo());
    }

    @Test
    void archivedCurrentPointerIsHiddenFromOffersButRetainsMetadataAndHistory() {
        f.facade.saveSku(f.ops, f.sku());
        f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
        var published = f.facade.catalog(f.ops, "RETAIL");
        var contract = published.skuVersions().getFirst().draft();
        int audits = auditCount();
        setSkuStatus("ARCHIVED");
        var view = f.facade.catalog(f.ops, "RETAIL");
        assertTrue(view.skus().isEmpty());
        assertEquals("sku-1", view.skuDefinitions().getFirst().currentVersionNo());
        assertEquals("ON_SALE", view.skuDefinitions().getFirst().saleStatus());
        var historical = view.skuVersions().getFirst();
        assertEquals("ARCHIVED", historical.status());
        assertEquals(contract, historical.draft());
        assertEquals(published.skuVersions().getFirst().version(), historical.version());
        assertEquals(published.skuVersions().getFirst().revision(), historical.revision());
        assertEquals(audits, auditCount());
    }

    @Test
    void publishedCandidateCanBeDeliberatelyReusedWithoutChangingItsContract() {
        f.facade.saveSku(f.ops, f.sku());
        f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
        var before = f.facade.catalog(f.ops, "RETAIL").skuVersions();
        f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 1));
        var after = f.facade.catalog(f.ops, "RETAIL");
        assertEquals(2, after.catalogVersion());
        assertEquals("sku-1", after.skus().getFirst().skuVersionNo());
        assertEquals(before, after.skuVersions());
    }

    @Test
    void selectedFreeAfterStateReplacesOldCurrentFreeForPackageEligibility() {
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        // Save while current FREE only contains BASE; expansion must not mutate this draft.
        f.facade.saveSku(f.ops, endPackage());
        f.facade.savePlan(f.ops, f.plan("FREE", "free-other", List.of("BASE", "POS")));
        f.facade.publishIndustry(f.ops, selectedFree("free-other", List.of(), 1));
        var contract = f.facade.catalog(f.ops, "RETAIL").skuVersions().getFirst().draft();
        f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 2));
        var view = f.facade.catalog(f.ops, "RETAIL");
        assertEquals(3, view.catalogVersion());
        assertEquals("sku-1", view.skus().getFirst().skuVersionNo());
        assertEquals(contract.components(), view.skuVersions().getFirst().draft().components());
        assertEquals(contract.price(), view.skuVersions().getFirst().draft().price());
        assertEquals(
                List.of("BASE"),
                view.plans().stream()
                        .filter(p -> p.tier() == PlanTier.FREE)
                        .findFirst()
                        .orElseThrow()
                        .entitlementCodes());
        assertEquals(
                List.of("BASE", "POS"),
                view.planVersions().stream()
                        .filter(p -> p.draft().planVersionNo().equals("free-other"))
                        .findFirst()
                        .orElseThrow()
                        .draft()
                        .entitlementCodes());
    }

    private SkuDraft endPackage() {
        return f.sku(
                "sku-1",
                List.of(
                        f.component(
                                "pos",
                                ComponentKind.END_PACKAGE,
                                null,
                                "POS",
                                null,
                                30,
                                1,
                                100,
                                false)));
    }

    private IndustryPublicationCommand selectedFree(String free, List<String> skus, long version) {
        var c = f.publication(skus, version);
        return new IndustryPublicationCommand(
                c.operationNo(),
                c.typeCode(),
                free,
                c.proPlanVersionNo(),
                c.chainPlanVersionNo(),
                skus,
                version,
                c.expectedPlanVersions(),
                c.expectedSkuVersions(),
                c.expectedPlanDefinitionVersions(),
                c.expectedSkuDefinitionVersions(),
                c.reason());
    }
}
