package ai.neargo.commerce.core.catalog.plan;

import static ai.neargo.commerce.core.catalog.CatalogSupport.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.catalog.product.SkuPublicationService;

import java.util.*;

public final class IndustryPublicationService {
    private final CatalogSupport s;
    private final SkuPublicationService skus;

    public IndustryPublicationService(CatalogSupport s, SkuPublicationService skus) {
        this.s = s;
        this.skus = skus;
    }

    public DraftSaveResult save(ActorContext a, PlanDraft command) {
        s.authorize(a, "edit");
        boolean creating = command.planVersionNo() == null;
        String no = creating ? s.nextId("plan-version") : command.planVersionNo();
        var d =
                new PlanDraft(
                        command.planNo(),
                        no,
                        command.typeCode(),
                        command.tier(),
                        command.displayName(),
                        command.summary(),
                        command.entitlementCodes(),
                        command.policyVersion(),
                        command.expectedVersion(),
                        command.reason());
        code(d.planNo(), 64);
        code(d.planVersionNo(), 64);
        text(d.displayName(), 64, "displayName");
        text(d.policyVersion(), 64, "policyVersion");
        check(d.summary() == null || d.summary().length() <= 512, "summary too long");
        unique(d.entitlementCodes());
        return s.write(
                a,
                "edit",
                d.reason(),
                "PLAN_VERSION",
                d.planVersionNo(),
                "PLAN_DRAFT_SAVED",
                null,
                state -> {
                    var plan = require(state.plans, d.planNo());
                    check(
                            plan.typeCode().equals(d.typeCode()) && plan.tier() == d.tier(),
                            "stable Plan identity/type/tier mismatch");
                    d.entitlementCodes()
                            .forEach(
                                    c ->
                                            check(
                                                    state.ends.values().stream()
                                                            .anyMatch(
                                                                    e ->
                                                                            e.draft()
                                                                                    .entitlementCode()
                                                                                    .equals(c)),
                                                    "unknown entitlement"));
                    var old = state.planVersions.get(d.planVersionNo());
                    check(
                            creating ? old == null : old != null,
                            creating
                                    ? "generated Plan version collision"
                                    : "editing unknown draft version");
                    if (old != null && !old.status().equals("DRAFT"))
                        throw new CatalogException(
                                CatalogErrorCode.IMMUTABLE_VERSION, "published Plan immutable");
                    expected(d.expectedVersion(), old == null ? 0 : old.version());
                    check(
                            old == null || old.draft().planNo().equals(d.planNo()),
                            "version cannot move Plan");
                    int revision =
                            old == null
                                    ? state.planVersions.values().stream()
                                                    .filter(
                                                            v ->
                                                                    v.draft()
                                                                            .planNo()
                                                                            .equals(d.planNo()))
                                                    .mapToInt(CatalogState.PlanVersion::revision)
                                                    .max()
                                                    .orElse(0)
                                            + 1
                                    : old.revision();
                    long v = old == null ? 1 : old.version() + 1;
                    state.planVersions.put(
                            d.planVersionNo(),
                            new CatalogState.PlanVersion(d, revision, "DRAFT", v, null, null));
                    return new DraftSaveResult(d.planNo(), d.planVersionNo(), v);
                });
    }

    public PublicationResult publish(ActorContext a, IndustryPublicationCommand d) {
        if (d.operationNo() != null) code(d.operationNo(), 64);
        unique(d.candidateSkuVersionNos());
        return s.write(
                a,
                "publish",
                d.reason(),
                "TYPE",
                d.typeCode(),
                "INDUSTRY_PUBLISHED",
                d.operationNo(),
                state -> {
                    var type = require(state.types, d.typeCode());
                    expected(d.expectedCatalogVersion(), type.catalogVersion());
                    code(d.freePlanVersionNo(), 64);
                    code(d.proPlanVersionNo(), 64);
                    code(d.chainPlanVersionNo(), 64);
                    var versions =
                            List.of(
                                    d.freePlanVersionNo(),
                                    d.proPlanVersionNo(),
                                    d.chainPlanVersionNo());
                    unique(versions);
                    check(
                            state.plans.values().stream()
                                            .filter(p -> p.typeCode().equals(d.typeCode()))
                                            .count()
                                    == 3,
                            "exactly three stable Plans required");
                    // Selected content and every affected live master belong to the same preview.
                    for (var definition : state.plans.values())
                        if (definition.typeCode().equals(d.typeCode())) {
                            check(
                                    d.expectedPlanDefinitionVersions()
                                            .containsKey(definition.planNo()),
                                    "Plan definition row version required");
                            expected(
                                    d.expectedPlanDefinitionVersions().get(definition.planNo()),
                                    definition.version());
                        }
                    for (String no : d.candidateSkuVersionNos()) {
                        var candidate = require(state.skuVersions, no);
                        var master = require(state.skus, candidate.draft().skuNo());
                        check(
                                d.expectedSkuDefinitionVersions().containsKey(master.skuNo()),
                                "SKU definition row version required");
                        expected(
                                d.expectedSkuDefinitionVersions().get(master.skuNo()),
                                master.version());
                    }
                    var freeCodes =
                            Set.copyOf(
                                    require(state.planVersions, versions.getFirst())
                                            .draft()
                                            .entitlementCodes());
                    Set<String> lower = Set.of();
                    var required = s.readiness.requiredEntitlementCodes(a.tenantNo(), d.typeCode());
                    check(
                            required != null && !required.isEmpty(),
                            "trusted base-entry registry required");
                    for (int i = 0; i < 3; i++) {
                        var v = require(state.planVersions, versions.get(i));
                        check(
                                d.expectedPlanVersions().containsKey(versions.get(i)),
                                "selected Plan row version required");
                        expected(d.expectedPlanVersions().get(versions.get(i)), v.version());
                        var p = require(state.plans, v.draft().planNo());
                        check(
                                p.typeCode().equals(d.typeCode())
                                        && p.tier() == PlanTier.values()[i],
                                "exact FREE/PRO/CHAIN order required");
                        check(
                                v.status().equals("DRAFT") || v.status().equals("PUBLISHED"),
                                "invalid Plan publication status");
                        var codes = new HashSet<>(v.draft().entitlementCodes());
                        check(codes.containsAll(lower), "higher Plan must be superset");
                        if (i == 0) {
                            check(
                                    codes.containsAll(required),
                                    "FREE missing required base entries");
                            for (var e : state.ends.values())
                                if (e.draft().bootstrapRequired()
                                        && e.draft().allowedBusinessTypes().contains(d.typeCode()))
                                    check(
                                            codes.contains(e.draft().entitlementCode()),
                                            "FREE missing bootstrap end");
                        }
                        s.planEndsValid(a.tenantNo(), state, v.draft(), freeCodes);
                        lower = codes;
                    }
                    var packagePlans = new HashSet<>(versions);
                    var candidateSkuNos = new HashSet<String>();
                    String currency = null;
                    for (String no : d.candidateSkuVersionNos()) {
                        var sku = require(state.skuVersions, no);
                        check(
                                d.expectedSkuVersions().containsKey(no),
                                "selected SKU row version required");
                        expected(d.expectedSkuVersions().get(no), sku.version());
                        check(
                                candidateSkuNos.add(sku.draft().skuNo()),
                                "one candidate version per SKU required");
                        check(
                                sku.draft().components().stream()
                                        .filter(c -> c.kind() == ComponentKind.PLAN)
                                        .allMatch(c -> packagePlans.contains(c.planVersionNo())),
                                "industry package SKU must reference selected Plan versions");
                        check(
                                sku.draft().allowedBusinessTypes().contains(d.typeCode()),
                                "candidate SKU outside industry");
                        skus.validatePublication(a.tenantNo(), state, no, packagePlans);
                        if (currency == null) currency = sku.draft().price().currency();
                        else
                            check(
                                    currency.equals(sku.draft().price().currency()),
                                    "mixed publication currencies");
                    }
                    long now = s.clock.millis();
                    for (String no : versions) {
                        var v = state.planVersions.get(no);
                        if (v.status().equals("DRAFT"))
                            state.planVersions.put(
                                    no,
                                    new CatalogState.PlanVersion(
                                            v.draft(),
                                            v.revision(),
                                            "PUBLISHED",
                                            v.version() + 1,
                                            now,
                                            a.principalNo()));
                        var p = state.plans.get(v.draft().planNo());
                        state.plans.put(
                                p.planNo(),
                                new CatalogState.Plan(
                                        p.planNo(),
                                        p.typeCode(),
                                        p.tier(),
                                        no,
                                        "ON_SALE",
                                        p.version() + 1));
                    }
                    // A new current Plan must never leave old-Plan SKUs purchasable under the new
                    // industry catalogue.
                    for (var sku : List.copyOf(state.skus.values()))
                        if (sku.saleStatus().equals("ON_SALE")) {
                            var v = state.skuVersions.get(sku.currentVersionNo());
                            boolean obsolete =
                                    v.draft().components().stream()
                                            .anyMatch(
                                                    c -> {
                                                        if (c.kind() == ComponentKind.END_PACKAGE)
                                                            return v.draft()
                                                                            .allowedBusinessTypes()
                                                                            .contains(d.typeCode())
                                                                    && freeCodes.contains(
                                                                            c.entitlementCode());
                                                        if (c.kind() != ComponentKind.PLAN)
                                                            return false;
                                                        var pv =
                                                                state.planVersions.get(
                                                                        c.planVersionNo());
                                                        return pv.draft()
                                                                        .typeCode()
                                                                        .equals(d.typeCode())
                                                                && !packagePlans.contains(
                                                                        c.planVersionNo());
                                                    });
                            if (obsolete)
                                state.skus.put(
                                        sku.skuNo(),
                                        new CatalogState.Sku(
                                                sku.skuNo(),
                                                sku.productNo(),
                                                sku.title(),
                                                sku.maxPerOrder(),
                                                sku.currentVersionNo(),
                                                "OFF_SALE",
                                                sku.version() + 1));
                        }
                    for (String no : d.candidateSkuVersionNos())
                        skus.publishVersion(state, no, now);
                    long catalog = type.catalogVersion() + 1;
                    state.types.put(
                            d.typeCode(),
                            new CatalogState.Type(type.draft(), catalog, type.version() + 1));
                    var published = new ArrayList<>(versions);
                    published.addAll(d.candidateSkuVersionNos());
                    return new PublicationResult(
                            s.nextId("catalog-publication"), catalog, List.copyOf(published));
                });
    }
}
