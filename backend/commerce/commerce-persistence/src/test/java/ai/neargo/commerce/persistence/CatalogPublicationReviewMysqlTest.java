package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class CatalogPublicationReviewMysqlTest {
    @Test
    void preparedPackageCannotOverwriteStandaloneSkuPublication() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
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
            var selected = f.publication(List.of(first.draftVersionNo()), 1);
            var master = f.facade.catalog(f.ops, "RETAIL").skuDefinitions().getFirst();
            f.facade.publishSku(
                    f.ops,
                    new SkuPublicationCommand(
                            null,
                            second.draftVersionNo(),
                            second.version(),
                            master.version(),
                            "newer standalone"));
            int audits =
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class);
            assertEquals(
                    CatalogErrorCode.VERSION_CONFLICT,
                    assertThrows(
                                    CatalogException.class,
                                    () -> f.facade.publishIndustry(f.ops, selected))
                            .code());
            assertEquals(
                    second.draftVersionNo(),
                    f.facade.catalog(f.ops, "RETAIL").skus().getFirst().skuVersionNo());
            assertEquals(
                    audits,
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(SaleTarget.class)
    void preparedPackageCannotUndoNewerStop(SaleTarget target) {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
            var selected = f.publication(List.of("sku-1"), 1);
            var view = f.facade.catalog(f.ops, "RETAIL");
            String no;
            long version;
            switch (target) {
                case PLAN -> {
                    var p =
                            view.planDefinitions().stream()
                                    .filter(p1 -> p1.tier() == PlanTier.PRO)
                                    .findFirst()
                                    .orElseThrow();
                    no = p.planNo();
                    version = p.version();
                }
                case SKU -> {
                    var s = view.skuDefinitions().getFirst();
                    no = s.skuNo();
                    version = s.version();
                }
                case PRODUCT -> {
                    var p = view.products().getFirst();
                    no = p.draft().productNo();
                    version = p.version();
                }
                default -> throw new AssertionError();
            }
            f.facade.stopSale(f.ops, new StopSaleCommand(null, target, no, version, "newer stop"));
            var stopped = f.facade.catalog(f.ops, "RETAIL");
            int audits =
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class);
            assertEquals(
                    CatalogErrorCode.VERSION_CONFLICT,
                    assertThrows(
                                    CatalogException.class,
                                    () -> f.facade.publishIndustry(f.ops, selected))
                            .code());
            assertEquals(stopped, f.facade.catalog(f.ops, "RETAIL"));
            assertEquals(
                    audits,
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void packageRequiresEveryAffectedMasterToken(boolean omitPlan) {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            var old = f.publication(List.of("sku-1"), 0);
            var command =
                    new IndustryPublicationCommand(
                            old.operationNo(),
                            old.typeCode(),
                            old.freePlanVersionNo(),
                            old.proPlanVersionNo(),
                            old.chainPlanVersionNo(),
                            old.candidateSkuVersionNos(),
                            old.expectedCatalogVersion(),
                            old.expectedPlanVersions(),
                            old.expectedSkuVersions(),
                            omitPlan ? Map.of() : old.expectedPlanDefinitionVersions(),
                            omitPlan ? old.expectedSkuDefinitionVersions() : Map.of(),
                            old.reason());
            assertEquals(
                    CatalogErrorCode.INVALID_ARGUMENT,
                    assertThrows(
                                    CatalogException.class,
                                    () -> f.facade.publishIndustry(f.ops, command))
                            .code());
            assertEquals(0, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"WITHDRAWN", "DISABLED", "RETIRED", "SALE_DISABLED", "LEGACY_PROOF"})
    void planSkuRechecksCurrentEndBeforePublication(String mode) {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
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
                    db.jdbc.update(
                            "UPDATE sys_business_end SET client_id=NULL,acceptance_ref=NULL WHERE"
                                + " tenant_no='tenant-a' AND end_code='POS'");
                } else f.facade.saveBusinessEnd(f.ops, changed);
            }
            int audits =
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class);
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
            assertEquals(
                    audits,
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class));
        }
    }

    @Test
    void unknownEndTargetUsesTypedNotFound() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
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
                    assertThrows(CatalogException.class, () -> f.facade.saveSku(f.ops, draft))
                            .code());
        }
    }

    @Test
    void permanentFreeEndDoesNotNeedPaidSaleFlagInHigherPlan() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
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
                            null,
                            saved.draftVersionNo(),
                            saved.version(),
                            1,
                            "base stays permanent"));
            assertEquals(1, f.facade.catalog(f.ops, "RETAIL").skus().size());
        }
    }
}
