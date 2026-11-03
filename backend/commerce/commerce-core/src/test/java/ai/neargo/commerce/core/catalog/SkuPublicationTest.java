package ai.neargo.commerce.core.catalog;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class SkuPublicationTest {
    @Test
    void independentSkuRequiresPublishedPlan() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        f.facade.saveSku(f.ops, f.sku());
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.publishSku(
                                f.ops,
                                new SkuPublicationCommand("single", "sku-1", 1, 1, "publish")));
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        f.facade.publishSku(f.ops, new SkuPublicationCommand("single", "sku-1", 1, 1, "publish"));
        assertEquals(1, f.facade.catalog(f.ops, "RETAIL").skus().size());
    }

    @Test
    void bundleRejectsTwoPlansAndGiftWeightAndInvalidUnits() {
        for (int variant = 0; variant < 4; variant++) {
            var f = new CatalogFixture();
            f.setup();
            f.plans();
            f.product();
            var parts = new ArrayList<SkuComponent>();
            parts.add(
                    f.component("a", ComponentKind.PLAN, "pro-1", null, null, 365, 1, 100, false));
            if (variant == 0)
                parts.add(
                        f.component(
                                "b", ComponentKind.PLAN, "chain-1", null, null, 30, 1, 100, false));
            if (variant == 1)
                parts.add(
                        f.component(
                                "b",
                                ComponentKind.HARDWARE,
                                null,
                                null,
                                "device",
                                null,
                                1,
                                10,
                                true));
            if (variant == 2)
                parts.add(
                        f.component(
                                "b",
                                ComponentKind.HARDWARE,
                                null,
                                null,
                                "device",
                                30,
                                1,
                                10,
                                false));
            if (variant == 3)
                parts.add(
                        f.component(
                                "b",
                                ComponentKind.END_PACKAGE,
                                null,
                                "POS",
                                null,
                                null,
                                1,
                                10,
                                false));
            var d = f.sku("invalid", parts);
            assertThrows(CatalogException.class, () -> f.facade.saveSku(f.ops, d));
        }
    }

    @Test
    void freePermanentEndCannotBeSoldAndStopRetainsPublishedSnapshot() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        var d =
                f.sku(
                        "bad",
                        List.of(
                                f.component(
                                        "b",
                                        ComponentKind.END_PACKAGE,
                                        null,
                                        "BASE",
                                        null,
                                        30,
                                        1,
                                        10,
                                        false)));
        assertThrows(CatalogException.class, () -> f.facade.saveSku(f.ops, d));
        f.facade.saveSku(f.ops, f.sku());
        f.facade.publishSku(f.ops, new SkuPublicationCommand("single", "sku-1", 1, 1, "publish"));
        var old = f.repo.load("tenant-a", CatalogLockMode.NONE).skuVersions.get("sku-1");
        f.facade.stopSale(f.ops, new StopSaleCommand("stop", SaleTarget.SKU, "sku", 2, "normal"));
        assertEquals(old, f.repo.load("tenant-a", CatalogLockMode.NONE).skuVersions.get("sku-1"));
        assertTrue(f.facade.catalog(f.ops, "RETAIL").skus().isEmpty());
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.stopSale(
                                f.ops,
                                new StopSaleCommand(
                                        "stop-free", SaleTarget.PLAN, "RETAIL:FREE", 2, "no")));
    }

    @Test
    void requiredFreeEntryCannotBeSoldEvenBeforeFirstPublication() {
        var f = new CatalogFixture();
        f.setup();
        f.product();
        var old = f.endDraft("BASE", EndScope.STORE, false, true);
        var edited =
                new BusinessEndDraft(
                        old.code(),
                        old.entitlementCode(),
                        old.name(),
                        old.scope(),
                        old.allowedBusinessTypes(),
                        old.releaseStatus(),
                        true,
                        false,
                        true,
                        old.clientId(),
                        old.routePolicyRef(),
                        old.acceptanceRef(),
                        1,
                        "correct registry metadata");
        f.facade.saveBusinessEnd(f.ops, edited);
        var d =
                f.sku(
                        "bad",
                        List.of(
                                f.component(
                                        "base-gift",
                                        ComponentKind.END_PACKAGE,
                                        null,
                                        "BASE",
                                        null,
                                        30,
                                        1,
                                        10,
                                        false)));
        assertThrows(CatalogException.class, () -> f.facade.saveSku(f.ops, d));
    }

    @Test
    void digitalDurationOverflowIsRejectedBeforeSavingDraft() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        var d =
                f.sku(
                        "overflow",
                        List.of(
                                f.component(
                                        "overflow",
                                        ComponentKind.PLAN,
                                        "pro-1",
                                        null,
                                        null,
                                        Integer.MAX_VALUE,
                                        Integer.MAX_VALUE,
                                        100,
                                        false)));
        assertEquals(
                CatalogErrorCode.INVALID_ARGUMENT,
                assertThrows(CatalogException.class, () -> f.facade.saveSku(f.ops, d)).code());
    }

    @Test
    void differentDraftsCannotPublishOverSameObservedSkuMasterVersion() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        var first = f.facade.saveSku(f.ops, f.sku());
        var second =
                f.facade.saveSku(
                        f.ops,
                        f.sku(
                                "sku-2",
                                List.of(
                                        f.component(
                                                "second",
                                                ComponentKind.PLAN,
                                                "pro-1",
                                                null,
                                                null,
                                                30,
                                                1,
                                                100,
                                                false))));
        long master = f.facade.catalog(f.ops, "RETAIL").skuDefinitions().getFirst().version();
        f.facade.publishSku(
                f.ops,
                new SkuPublicationCommand(
                        null, first.draftVersionNo(), first.version(), master, "first"));
        assertEquals(
                CatalogErrorCode.VERSION_CONFLICT,
                assertThrows(
                                CatalogException.class,
                                () ->
                                        f.facade.publishSku(
                                                f.ops,
                                                new SkuPublicationCommand(
                                                        null,
                                                        second.draftVersionNo(),
                                                        second.version(),
                                                        master,
                                                        "stale master")))
                        .code());
        assertEquals(
                first.draftVersionNo(),
                f.facade.catalog(f.ops, "RETAIL").skus().getFirst().skuVersionNo());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"WITHDRAWN", "DISABLED", "RETIRED", "SALE_DISABLED", "LEGACY_PROOF"})
    void planSkuRechecksCurrentEndBeforePublication(String mode) {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        var saved = f.facade.saveSku(f.ops, f.sku());
        var old =
                f.facade.catalog(f.ops, "RETAIL").businessEnds().stream()
                        .filter(e -> e.draft().code().equals("POS"))
                        .findFirst()
                        .orElseThrow();
        var d = old.draft();
        if (mode.equals("WITHDRAWN")) f.accepted.remove("POS");
        else {
            var changed =
                    new BusinessEndDraft(
                            d.code(),
                            d.entitlementCode(),
                            d.name(),
                            d.scope(),
                            d.allowedBusinessTypes(),
                            mode.equals("RETIRED") ? ReleaseStatus.RETIRED : d.releaseStatus(),
                            !mode.equals("SALE_DISABLED"),
                            d.bootstrapRequired(),
                            !mode.equals("DISABLED"),
                            mode.equals("LEGACY_PROOF") ? null : d.clientId(),
                            d.routePolicyRef(),
                            mode.equals("LEGACY_PROOF") ? null : d.acceptanceRef(),
                            old.version(),
                            "lifecycle change");
            if (mode.equals("LEGACY_PROOF")) {
                var before = f.repo.load("tenant-a", CatalogLockMode.NONE);
                var after = before.copy();
                after.ends.put("POS", new CatalogState.End(changed, old.version() + 1));
                f.repo.save(
                        "tenant-a",
                        before,
                        after,
                        new CatalogWriteContext("operator-a", java.time.Instant.EPOCH));
            } else f.facade.saveBusinessEnd(f.ops, changed);
        }
        int audits = f.audits.size();
        assertEquals(
                CatalogErrorCode.INVALID_ARGUMENT,
                assertThrows(
                                CatalogException.class,
                                () ->
                                        f.facade.publishSku(
                                                f.ops,
                                                new SkuPublicationCommand(
                                                        null,
                                                        saved.draftVersionNo(),
                                                        saved.version(),
                                                        1,
                                                        "attempt new sale")))
                        .code());
        assertTrue(f.facade.catalog(f.ops, "RETAIL").skus().isEmpty());
        assertEquals(audits, f.audits.size());
    }

    @Test
    void unknownEndTargetUsesTypedNotFound() {
        var f = new CatalogFixture();
        f.setup();
        f.product();
        var draft =
                f.sku(
                        "unknown",
                        List.of(
                                f.component(
                                        "unknown",
                                        ComponentKind.END_PACKAGE,
                                        null,
                                        "MISSING",
                                        null,
                                        30,
                                        1,
                                        100,
                                        false)));
        assertEquals(
                CatalogErrorCode.NOT_FOUND,
                assertThrows(CatalogException.class, () -> f.facade.saveSku(f.ops, draft)).code());
    }

    @Test
    void permanentFreeEndDoesNotNeedPaidSaleFlagInHigherPlan() {
        var f = new CatalogFixture();
        f.setup();
        var end =
                f.facade.catalog(f.ops, "RETAIL").businessEnds().stream()
                        .filter(e -> e.draft().code().equals("POS"))
                        .findFirst()
                        .orElseThrow();
        var e = end.draft();
        f.facade.saveBusinessEnd(
                f.ops,
                new BusinessEndDraft(
                        e.code(),
                        e.entitlementCode(),
                        e.name(),
                        e.scope(),
                        e.allowedBusinessTypes(),
                        e.releaseStatus(),
                        false,
                        false,
                        true,
                        e.clientId(),
                        e.routePolicyRef(),
                        e.acceptanceRef(),
                        end.version(),
                        "permanent free"));
        f.facade.savePlan(f.ops, f.plan("FREE", "free-1", List.of("BASE", "POS")));
        f.facade.savePlan(f.ops, f.plan("PRO", "pro-1", List.of("BASE", "POS")));
        f.facade.savePlan(f.ops, f.plan("CHAIN", "chain-1", List.of("BASE", "POS", "KDS")));
        f.product();
        f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
        var saved = f.facade.saveSku(f.ops, f.sku());
        f.facade.publishSku(
                f.ops,
                new SkuPublicationCommand(
                        null, saved.draftVersionNo(), saved.version(), 1, "base stays permanent"));
        assertEquals(1, f.facade.catalog(f.ops, "RETAIL").skus().size());
    }
}
