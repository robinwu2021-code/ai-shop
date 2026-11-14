package ai.neargo.commerce.core.catalog;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;

class CatalogWritePermissionTest {
    @Test
    void nameAndSortNeedEditWhileEnabledNeedsPublish() {
        var f = new CatalogFixture();
        f.setup();
        f.capabilities.remove("commerce:catalog:publish");
        f.facade.saveBusinessType(
                f.ops, new BusinessTypeDraft("RETAIL", "新名字", 3, false, 1, "rename"));
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.saveBusinessType(
                                f.ops,
                                new BusinessTypeDraft("RETAIL", "新名字", 3, true, 2, "enable")));
        f.capabilities.add("commerce:catalog:publish");
        f.capabilities.remove("commerce:catalog:edit");
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.saveBusinessType(
                                f.ops,
                                new BusinessTypeDraft("RETAIL", "changed", 3, false, 2, "rename")));
    }

    @Test
    void enabledCreationCannotBypassDisabledBootstrap() {
        var f = new CatalogFixture();
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.saveBusinessType(
                                f.ops, new BusinessTypeDraft("FOOD", "餐饮", 0, true, 0, "create")));
        assertTrue(f.repo.load("tenant-a", CatalogLockMode.NONE).types.isEmpty());
    }

    @Test
    void merchantOrForgedSessionCannotUseOpsCapabilities() {
        var f = new CatalogFixture();
        var merchant = new ActorContext("tenant-a", SubjectKind.ACCOUNT, "owner", "trusted");
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.saveBusinessType(
                                merchant,
                                new BusinessTypeDraft("RETAIL", "零售", 0, false, 0, "create")));
        var forged = new ActorContext("tenant-a", SubjectKind.OPERATOR, "ops", "untrusted");
        assertThrows(CatalogException.class, () -> f.facade.catalog(forged, "RETAIL"));
    }

    @Test
    void acceptedWriteAuditsTrustedActorReasonAndVersions() {
        var f = new CatalogFixture();
        f.setup();
        assertTrue(
                f.audits.stream()
                        .anyMatch(
                                e ->
                                        e.payloadJson().contains("operator-a")
                                                && e.payloadJson().contains("create")
                                                && e.payloadJson().contains("beforeVersion")
                                                && e.payloadJson().contains("afterVersion")));
    }

    @Test
    void auditSeparatesLegallyIdenticalBusinessNumbersByEntityKind() {
        var f = new CatalogFixture();
        f.setup();
        f.accepted.add("RETAIL");
        f.facade.saveBusinessEnd(f.ops, f.endDraft("RETAIL", EndScope.STORE, false, true));
        var e = f.audits.getLast();
        assertEquals("CATALOG_END", e.aggregateType());
        assertEquals("END_SAVED", e.eventType());
        assertTrue(e.payloadJson().contains("\"beforeVersion\":0"));
        assertEquals(1, e.aggregateVersion());
    }

    @Test
    void publicMetadataCarriesCorrectMasterVersionsForPublishOnlyEnableAndStop() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.facade.publishIndustry(f.ops, f.publication(java.util.List.of(), 0));
        var before = f.facade.catalog(f.ops, "RETAIL");
        assertEquals(2, before.businessType().version());
        assertEquals(3, before.planDefinitions().size());
        f.capabilities.remove("commerce:catalog:edit");
        f.facade.saveBusinessType(
                f.ops,
                new BusinessTypeDraft(
                        "RETAIL", "零售", 1, true, before.businessType().version(), "enable"));
        var current = f.facade.catalog(f.ops, "RETAIL");
        assertTrue(current.enabled());
        var plan =
                current.planDefinitions().stream()
                        .filter(p -> p.tier() == PlanTier.PRO)
                        .findFirst()
                        .orElseThrow();
        f.facade.stopSale(
                f.ops,
                new StopSaleCommand(
                        "stop", SaleTarget.PLAN, plan.planNo(), plan.version(), "normal"));
        assertEquals(
                "OFF_SALE",
                f.facade.catalog(f.ops, "RETAIL").planDefinitions().stream()
                        .filter(p -> p.tier() == PlanTier.PRO)
                        .findFirst()
                        .orElseThrow()
                        .saleStatus());
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.saveBusinessType(
                                f.ops,
                                new BusinessTypeDraft(
                                        "RETAIL", "changed", 1, false, 3, "both fields")));
    }

    @Test
    void moduleErrorsDistinguishInvalidMissingStaleAndForbiddenOutcomes() {
        var f = new CatalogFixture();
        f.setup();
        assertEquals(
                CatalogErrorCode.INVALID_ARGUMENT,
                assertThrows(
                                CatalogException.class,
                                () ->
                                        f.facade.saveBusinessType(
                                                f.ops,
                                                new BusinessTypeDraft(
                                                        "FOOD", "餐饮", 0, true, 0, "bad create")))
                        .code());
        assertEquals(
                CatalogErrorCode.NOT_FOUND,
                assertThrows(CatalogException.class, () -> f.facade.catalog(f.ops, "MISSING"))
                        .code());
        assertEquals(
                CatalogErrorCode.VERSION_CONFLICT,
                assertThrows(
                                CatalogException.class,
                                () ->
                                        f.facade.saveBusinessType(
                                                f.ops,
                                                new BusinessTypeDraft(
                                                        "RETAIL", "零售", 1, false, 99, "stale")))
                        .code());
        var merchant = new ActorContext("tenant-a", SubjectKind.ACCOUNT, "owner", "trusted");
        assertEquals(
                CatalogErrorCode.FORBIDDEN,
                assertThrows(CatalogException.class, () -> f.facade.catalog(merchant, "RETAIL"))
                        .code());
    }
}
