package ai.neargo.commerce.core.catalog;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class IndustryPublicationTest {
    @Test
    void firstPackagePublishesMatchingPlanAndSkuDraftsAtomically() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        f.facade.saveSku(f.ops, f.sku());
        var r = f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
        assertEquals(1, r.catalogVersion());
        assertEquals(4, r.publishedVersionNos().size());
        assertEquals(3, f.facade.catalog(f.ops, "RETAIL").plans().size());
        assertEquals("sku-1", f.facade.catalog(f.ops, "RETAIL").skus().getFirst().skuVersionNo());
    }

    @Test
    void wrongTierAndMissingFreeBaseAndNonSupersetCannotPublish() {
        for (int variant = 0; variant < 3; variant++) {
            var f = new CatalogFixture();
            f.setup();
            f.facade.savePlan(
                    f.ops, f.plan("FREE", "free-1", variant == 1 ? List.of() : List.of("BASE")));
            f.facade.savePlan(f.ops, f.plan("PRO", "pro-1", List.of("BASE", "POS")));
            f.facade.savePlan(
                    f.ops,
                    f.plan(
                            "CHAIN",
                            "chain-1",
                            variant == 2 ? List.of("BASE") : List.of("BASE", "POS")));
            var c =
                    variant == 0
                            ? new IndustryPublicationCommand(
                                    "bad",
                                    "RETAIL",
                                    "pro-1",
                                    "free-1",
                                    "chain-1",
                                    List.of(),
                                    0,
                                    f.planTokens("RETAIL"),
                                    f.skuTokens("RETAIL"),
                                    "bad tier")
                            : f.publication(List.of(), 0);
            assertThrows(CatalogException.class, () -> f.facade.publishIndustry(f.ops, c));
            assertEquals(0, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
            assertTrue(f.facade.catalog(f.ops, "RETAIL").plans().isEmpty());
        }
    }

    @Test
    void duplicateCodesAreRejectedBeforeSetConversion() {
        var f = new CatalogFixture();
        f.setup();
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.savePlan(
                                f.ops, f.plan("PRO", "bad", List.of("BASE", "POS", "POS"))));
    }

    @Test
    void registryRejectionAndSystemEndCannotBeSold() {
        var f = new CatalogFixture();
        f.setup();
        f.accepted.remove("POS");
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.saveBusinessEnd(
                                f.ops, f.endDraft("POS-NEW", EndScope.STORE, false, true)));
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.saveBusinessEnd(
                                f.ops, f.endDraft("BASE-OPS", EndScope.SYSTEM, false, true)));
    }

    @Test
    void stalePublicationFailsAndPublishedVersionCannotBeEdited() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        assertThrows(
                CatalogException.class,
                () -> f.facade.publishIndustry(f.ops, f.publication(List.of(), 0)));
        var d = f.plan("PRO", "pro-1", List.of("BASE"));
        assertThrows(CatalogException.class, () -> f.facade.savePlan(f.ops, d));
        assertEquals(
                List.of("BASE", "POS"),
                f.facade.catalog(f.ops, "RETAIL").plans().stream()
                        .filter(p -> p.planVersionNo().equals("pro-1"))
                        .findFirst()
                        .orElseThrow()
                        .entitlementCodes());
    }

    @Test
    void generatedVersionIdsAreReturnedAndUnknownExplicitDraftTargetsAreRejected() {
        var f = new CatalogFixture();
        f.setup();
        var command = f.plan("FREE", "free-1", List.of("BASE"));
        assertNull(command.planVersionNo());
        var saved = f.facade.savePlan(f.ops, command);
        assertEquals("free-1", saved.draftVersionNo());
        assertEquals(1, saved.version());
        var forged =
                new PlanDraft(
                        "RETAIL:PRO",
                        "client-new-version",
                        "RETAIL",
                        ai.neargo.commerce.api.common.PlanTier.PRO,
                        "PRO",
                        "",
                        List.of("BASE"),
                        "policy",
                        0,
                        "create");
        assertThrows(CatalogException.class, () -> f.facade.savePlan(f.ops, forged));
    }

    @Test
    void packageCannotReenableOldSkuAgainstNewCurrentPlan() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        f.facade.saveSku(f.ops, f.sku());
        f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
        f.facade.savePlan(f.ops, f.plan("PRO", "pro-2", List.of("BASE", "POS")));
        var d =
                new IndustryPublicationCommand(
                        "bad-package",
                        "RETAIL",
                        "free-1",
                        "pro-2",
                        "chain-1",
                        List.of("sku-1"),
                        1,
                        f.planTokens("RETAIL"),
                        f.skuTokens("RETAIL"),
                        "mismatch");
        assertThrows(CatalogException.class, () -> f.facade.publishIndustry(f.ops, d));
        assertEquals(1, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
    }

    @Test
    void newlyPermanentFreeEndStopsPreviouslySoldFiniteEndSku() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        var d =
                f.sku(
                        "sku-1",
                        List.of(
                                f.component(
                                        "finite-pos",
                                        ai.neargo.commerce.api.common.ComponentKind.END_PACKAGE,
                                        null,
                                        "POS",
                                        null,
                                        30,
                                        1,
                                        100,
                                        false)));
        f.facade.saveSku(f.ops, d);
        f.facade.publishSku(f.ops, new SkuPublicationCommand("single", "sku-1", 1, 1, "publish"));
        f.facade.savePlan(f.ops, f.plan("FREE", "free-2", List.of("BASE", "POS")));
        f.facade.publishIndustry(
                f.ops,
                new IndustryPublicationCommand(
                        "expand",
                        "RETAIL",
                        "free-2",
                        "pro-1",
                        "chain-1",
                        List.of(),
                        1,
                        f.planTokens("RETAIL"),
                        f.skuTokens("RETAIL"),
                        "base expansion"));
        assertTrue(f.facade.catalog(f.ops, "RETAIL").skus().isEmpty());
    }

    @Test
    void staleSelectedPlanContentVersionRejectsEntirePublication() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        var command = f.publication(List.of(), 0);
        long old = command.expectedPlanVersions().get("pro-1");
        f.facade.savePlan(
                f.ops,
                new PlanDraft(
                        "RETAIL:PRO",
                        "pro-1",
                        "RETAIL",
                        ai.neargo.commerce.api.common.PlanTier.PRO,
                        "PRO",
                        "",
                        List.of("BASE", "POS", "KDS"),
                        "policy",
                        old,
                        "edit rights after preview"));
        assertEquals(
                CatalogErrorCode.VERSION_CONFLICT,
                assertThrows(CatalogException.class, () -> f.facade.publishIndustry(f.ops, command))
                        .code());
        var view = f.facade.catalog(f.ops, "RETAIL");
        assertEquals(0, view.catalogVersion());
        assertTrue(view.plans().isEmpty());
    }

    @Test
    void staleSelectedSkuPriceVersionRejectsEntirePublication() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        var saved = f.facade.saveSku(f.ops, f.sku());
        var command = f.publication(List.of(saved.draftVersionNo()), 0);
        var old = f.facade.catalog(f.ops, "RETAIL").skuVersions().getFirst();
        var d = old.draft();
        f.facade.saveSku(
                f.ops,
                new SkuDraft(
                        d.productNo(),
                        d.skuNo(),
                        d.skuVersionNo(),
                        d.title(),
                        d.listPrice(),
                        new ai.neargo.commerce.api.common.Money("CNY", 8000),
                        d.allowedBusinessTypes(),
                        d.refundPolicySnapshot(),
                        d.components(),
                        d.maxPerOrder(),
                        old.version(),
                        "edit price after preview"));
        assertEquals(
                CatalogErrorCode.VERSION_CONFLICT,
                assertThrows(CatalogException.class, () -> f.facade.publishIndustry(f.ops, command))
                        .code());
        var view = f.facade.catalog(f.ops, "RETAIL");
        assertEquals(0, view.catalogVersion());
        assertTrue(view.plans().isEmpty());
        assertTrue(view.skus().isEmpty());
    }
}
