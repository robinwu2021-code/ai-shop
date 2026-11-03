package ai.neargo.commerce.core.catalog.product;

import static ai.neargo.commerce.core.catalog.CatalogSupport.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.*;

import java.util.*;

public final class SkuPublicationService {
    private final CatalogSupport s;

    public SkuPublicationService(CatalogSupport s) {
        this.s = s;
    }

    public DraftSaveResult saveProduct(ActorContext a, ProductDraft d) {
        code(d.productNo(), 64);
        code(d.categoryCode(), 32);
        text(d.name(), 128, "name");
        unique(d.allowedBusinessTypes());
        check(d.kind() != null, "product kind required");
        check(!d.allowedBusinessTypes().isEmpty(), "product scope required");
        return s.write(
                a,
                "edit",
                d.reason(),
                "PRODUCT",
                d.productNo(),
                "PRODUCT_SAVED",
                null,
                state -> {
                    d.allowedBusinessTypes().forEach(t -> require(state.types, t));
                    var old = state.products.get(d.productNo());
                    expected(d.expectedVersion(), old == null ? 0 : old.version());
                    // Published metadata/target scope changes require a new SPU, so old contracts
                    // never silently change target.
                    if (old != null
                            && state.skus.values().stream()
                                    .anyMatch(v -> v.productNo().equals(d.productNo())))
                        check(
                                old.draft().kind() == d.kind()
                                        && old.draft()
                                                .allowedBusinessTypes()
                                                .equals(d.allowedBusinessTypes()),
                                "referenced product kind/scope immutable");
                    long v = old == null ? 1 : old.version() + 1;
                    state.products.put(
                            d.productNo(),
                            new CatalogState.Product(
                                    d, old == null ? "DRAFT" : old.saleStatus(), v));
                    return new DraftSaveResult(d.productNo(), null, v);
                });
    }

    public DraftSaveResult save(ActorContext a, SkuDraft command) {
        s.authorize(a, "edit");
        boolean creating = command.skuVersionNo() == null;
        String no = creating ? s.nextId("sku-version") : command.skuVersionNo();
        var suppliedComponentNos =
                command.components().stream()
                        .map(SkuComponent::componentNo)
                        .filter(Objects::nonNull)
                        .toList();
        var components =
                command.components().stream()
                        .map(
                                c ->
                                        new SkuComponent(
                                                c.componentNo() == null
                                                        ? s.nextId("sku-component")
                                                        : c.componentNo(),
                                                c.kind(),
                                                c.planVersionNo(),
                                                c.entitlementCode(),
                                                c.hardwareSkuNo(),
                                                c.periodDays(),
                                                c.unitCount(),
                                                c.allocationWeight(),
                                                c.gift(),
                                                c.activationPolicy()))
                        .toList();
        var d =
                new SkuDraft(
                        command.productNo(),
                        command.skuNo(),
                        no,
                        command.title(),
                        command.listPrice(),
                        command.price(),
                        command.allowedBusinessTypes(),
                        command.refundPolicySnapshot(),
                        components,
                        command.maxPerOrder(),
                        command.expectedVersion(),
                        command.reason());
        code(d.skuNo(), 64);
        code(d.skuVersionNo(), 64);
        text(d.title(), 128, "title");
        return s.write(
                a,
                "edit",
                d.reason(),
                "SKU_VERSION",
                d.skuVersionNo(),
                "SKU_DRAFT_SAVED",
                null,
                state -> {
                    validate(a.tenantNo(), state, d, state.planVersions.keySet());
                    var old = state.skuVersions.get(d.skuVersionNo());
                    check(
                            creating ? old == null : old != null,
                            creating
                                    ? "generated SKU version collision"
                                    : "editing unknown draft version");
                    check(
                            suppliedComponentNos.isEmpty()
                                    || (old != null
                                            && old.draft().components().stream()
                                                    .map(SkuComponent::componentNo)
                                                    .toList()
                                                    .containsAll(suppliedComponentNos)),
                            "component IDs must be generated for new components");
                    check(
                            state.skuVersions.values().stream()
                                    .filter(v -> !v.draft().skuVersionNo().equals(d.skuVersionNo()))
                                    .flatMap(v -> v.draft().components().stream())
                                    .noneMatch(
                                            c ->
                                                    d.components().stream()
                                                            .anyMatch(
                                                                    n ->
                                                                            n.componentNo()
                                                                                    .equals(
                                                                                            c
                                                                                                    .componentNo()))),
                            "component number belongs to another version");
                    if (old != null && !old.status().equals("DRAFT"))
                        throw new CatalogException(
                                CatalogErrorCode.IMMUTABLE_VERSION, "published SKU immutable");
                    expected(d.expectedVersion(), old == null ? 0 : old.version());
                    check(
                            old == null || old.draft().skuNo().equals(d.skuNo()),
                            "version cannot move SKU");
                    var sku = state.skus.get(d.skuNo());
                    if (sku == null)
                        state.skus.put(
                                d.skuNo(),
                                new CatalogState.Sku(
                                        d.skuNo(),
                                        d.productNo(),
                                        d.title(),
                                        d.maxPerOrder(),
                                        null,
                                        "DRAFT",
                                        1));
                    else check(sku.productNo().equals(d.productNo()), "SKU cannot move SPU");
                    int revision =
                            old == null
                                    ? state.skuVersions.values().stream()
                                                    .filter(
                                                            v ->
                                                                    v.draft()
                                                                            .skuNo()
                                                                            .equals(d.skuNo()))
                                                    .mapToInt(CatalogState.SkuVersion::revision)
                                                    .max()
                                                    .orElse(0)
                                            + 1
                                    : old.revision();
                    long v = old == null ? 1 : old.version() + 1;
                    state.skuVersions.put(
                            d.skuVersionNo(),
                            new CatalogState.SkuVersion(d, revision, "DRAFT", v, null));
                    return new DraftSaveResult(d.skuNo(), d.skuVersionNo(), v);
                });
    }

    public void validate(String tenant, CatalogState state, SkuDraft d, Set<String> packagePlans) {
        text(d.title(), 128, "SKU version title snapshot");
        var product = require(state.products, d.productNo());
        unique(d.allowedBusinessTypes());
        check(
                !d.allowedBusinessTypes().isEmpty()
                        && product.draft()
                                .allowedBusinessTypes()
                                .containsAll(d.allowedBusinessTypes()),
                "SKU scope outside product");
        d.allowedBusinessTypes().forEach(t -> require(state.types, t));
        check(
                d.price() != null
                        && d.listPrice() != null
                        && d.price().minor() >= 0
                        && d.listPrice().minor() >= d.price().minor(),
                "invalid money");
        check(
                d.price().currency() != null
                        && d.price().currency().matches("[A-Z]{3}")
                        && d.price().currency().equals(d.listPrice().currency()),
                "invalid currency");
        check(d.maxPerOrder() > 0 && !d.components().isEmpty(), "quantity/components required");
        text(d.refundPolicySnapshot(), 65535, "refund policy JSON");
        JsonObject.validate(d.refundPolicySnapshot());
        var componentNos = new HashSet<String>();
        long totalWeight = 0;
        int plans = 0;
        for (var c : d.components()) {
            code(c.componentNo(), 64);
            check(componentNos.add(c.componentNo()), "duplicate component");
            check(
                    c.kind() != null && c.unitCount() > 0 && c.allocationWeight() >= 0,
                    "invalid component quantity/weight");
            check(!c.gift() || c.allocationWeight() == 0, "gift allocation must be zero");
            check(
                    "ON_FULFILLMENT".equals(c.activationPolicy())
                            || "AFTER_PREVIOUS".equals(c.activationPolicy()),
                    "unknown activation policy");
            try {
                totalWeight = Math.addExact(totalWeight, c.allocationWeight());
            } catch (ArithmeticException ex) {
                throw new CatalogException(
                        CatalogErrorCode.INVALID_ARGUMENT, "allocation weight overflow");
            }
            switch (c.kind()) {
                case PLAN -> {
                    check(++plans <= 1, "at most one base Plan component");
                    check(
                            c.planVersionNo() != null
                                    && c.entitlementCode() == null
                                    && c.hardwareSkuNo() == null
                                    && c.periodDays() != null
                                    && c.periodDays() > 0,
                            "Plan target/period required");
                    var pv = require(state.planVersions, c.planVersionNo());
                    var p = require(state.plans, pv.draft().planNo());
                    check(p.tier() != PlanTier.FREE, "FREE cannot be sold as finite Plan");
                    check(
                            d.allowedBusinessTypes().size() == 1
                                    && d.allowedBusinessTypes().contains(p.typeCode()),
                            "Plan/SKU industry mismatch");
                    if (!packagePlans.contains(c.planVersionNo()))
                        check(
                                pv.status().equals("PUBLISHED")
                                        && c.planVersionNo().equals(p.currentVersionNo())
                                        && p.saleStatus().equals("ON_SALE"),
                                "independent SKU needs current published on-sale Plan");
                }
                case END_PACKAGE -> {
                    check(
                            c.planVersionNo() == null
                                    && c.hardwareSkuNo() == null
                                    && c.entitlementCode() != null
                                    && c.periodDays() != null
                                    && c.periodDays() > 0,
                            "end target/period required");
                    var e =
                            state.ends.values().stream()
                                    .filter(
                                            x ->
                                                    x.draft()
                                                            .entitlementCode()
                                                            .equals(c.entitlementCode()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "unknown end target"));
                    for (String type : d.allowedBusinessTypes()) {
                        s.endValid(tenant, type, e, true);
                        var required = s.readiness.requiredEntitlementCodes(tenant, type);
                        check(
                                required != null && !required.isEmpty(),
                                "trusted base-entry registry required");
                        check(
                                !required.contains(c.entitlementCode()),
                                "required FREE end cannot be sold");
                        check(!e.draft().bootstrapRequired(), "FREE permanent end cannot be sold");
                        var free =
                                state.plans.values().stream()
                                        .filter(
                                                p ->
                                                        p.typeCode().equals(type)
                                                                && p.tier() == PlanTier.FREE)
                                        .findFirst()
                                        .orElseThrow();
                        if (free.currentVersionNo() != null)
                            check(
                                    !state.planVersions
                                            .get(free.currentVersionNo())
                                            .draft()
                                            .entitlementCodes()
                                            .contains(c.entitlementCode()),
                                    "FREE permanent end cannot be sold");
                        for (String no : packagePlans) {
                            var pv = state.planVersions.get(no);
                            if (pv.draft().typeCode().equals(type)
                                    && pv.draft().tier() == PlanTier.FREE)
                                check(
                                        !pv.draft()
                                                .entitlementCodes()
                                                .contains(c.entitlementCode()),
                                        "candidate FREE permanent end cannot be sold");
                        }
                    }
                }
                case HARDWARE -> {
                    code(c.hardwareSkuNo(), 64);
                    check(
                            c.planVersionNo() == null
                                    && c.entitlementCode() == null
                                    && c.periodDays() == null,
                            "hardware uses pieces, no digital period");
                    check(
                            "ON_FULFILLMENT".equals(c.activationPolicy()),
                            "hardware activation must be ON_FULFILLMENT");
                }
            }
            if (c.kind() != ComponentKind.HARDWARE) {
                try {
                    Math.multiplyExact(
                            Math.multiplyExact(c.periodDays().longValue(), 86_400_000L),
                            c.unitCount());
                } catch (ArithmeticException overflow) {
                    throw new CatalogException(
                            CatalogErrorCode.INVALID_ARGUMENT,
                            "digital duration exceeds UTC millisecond range");
                }
            }
        }
        check(d.price().minor() == 0 || totalWeight > 0, "paid SKU needs allocation basis");
        switch (product.draft().kind()) {
            case PLAN ->
                    check(
                            d.components().size() == 1 && plans == 1,
                            "PLAN SPU requires one Plan component");
            case END_ACCESS ->
                    check(
                            d.components().size() == 1
                                    && d.components().getFirst().kind()
                                            == ComponentKind.END_PACKAGE,
                            "END_ACCESS SPU requires one end component");
            case HARDWARE ->
                    check(
                            d.components().stream()
                                    .allMatch(c -> c.kind() == ComponentKind.HARDWARE),
                            "HARDWARE SPU requires hardware");
            case BUNDLE -> {}
        }
    }

    public PublicationResult publish(ActorContext a, SkuPublicationCommand d) {
        if (d.operationNo() != null) code(d.operationNo(), 64);
        return s.write(
                a,
                "publish",
                d.reason(),
                "SKU_VERSION",
                d.skuVersionNo(),
                "SKU_PUBLISHED",
                d.operationNo(),
                state -> {
                    var v = require(state.skuVersions, d.skuVersionNo());
                    expected(d.expectedVersion(), v.version());
                    var sku = require(state.skus, v.draft().skuNo());
                    expected(d.expectedSkuVersion(), sku.version());
                    check(v.status().equals("DRAFT"), "only draft SKU can publish");
                    validate(a.tenantNo(), state, v.draft(), Set.of());
                    publishVersion(state, d.skuVersionNo(), s.clock.millis());
                    return new PublicationResult(
                            s.nextId("catalog-publication"), 0, List.of(d.skuVersionNo()));
                });
    }

    public void publishVersion(CatalogState state, String no, long now) {
        var v = require(state.skuVersions, no);
        if (v.status().equals("DRAFT"))
            state.skuVersions.put(
                    no,
                    new CatalogState.SkuVersion(
                            v.draft(), v.revision(), "PUBLISHED", v.version() + 1, now));
        var sku = require(state.skus, v.draft().skuNo());
        state.skus.put(
                sku.skuNo(),
                new CatalogState.Sku(
                        sku.skuNo(),
                        sku.productNo(),
                        v.draft().title(),
                        v.draft().maxPerOrder(),
                        no,
                        "ON_SALE",
                        sku.version() + 1));
        var product = require(state.products, sku.productNo());
        if (!product.saleStatus().equals("ON_SALE"))
            state.products.put(
                    sku.productNo(),
                    new CatalogState.Product(product.draft(), "ON_SALE", product.version() + 1));
    }

    public DraftSaveResult stop(ActorContext a, StopSaleCommand d) {
        if (d.operationNo() != null) code(d.operationNo(), 64);
        check(d.target() != null, "sale target required");
        return s.write(
                a,
                "publish",
                d.reason(),
                d.target().name(),
                d.targetNo(),
                d.target().name() + "_STOPPED",
                d.operationNo(),
                state -> {
                    long v;
                    switch (d.target()) {
                        case PLAN -> {
                            var p = require(state.plans, d.targetNo());
                            check(p.tier() != PlanTier.FREE, "FREE cannot be stopped");
                            expected(d.expectedVersion(), p.version());
                            v = p.version() + 1;
                            state.plans.put(
                                    p.planNo(),
                                    new CatalogState.Plan(
                                            p.planNo(),
                                            p.typeCode(),
                                            p.tier(),
                                            p.currentVersionNo(),
                                            "OFF_SALE",
                                            v));
                            for (var sku : List.copyOf(state.skus.values()))
                                if (sku.currentVersionNo() != null
                                        && state
                                                .skuVersions
                                                .get(sku.currentVersionNo())
                                                .draft()
                                                .components()
                                                .stream()
                                                .anyMatch(
                                                        c ->
                                                                c.kind() == ComponentKind.PLAN
                                                                        && state.planVersions
                                                                                .get(
                                                                                        c
                                                                                                .planVersionNo())
                                                                                .draft()
                                                                                .planNo()
                                                                                .equals(
                                                                                        p
                                                                                                .planNo())))
                                    stopSku(state, sku);
                        }
                        case SKU -> {
                            var sku = require(state.skus, d.targetNo());
                            expected(d.expectedVersion(), sku.version());
                            v = sku.version() + 1;
                            stopSku(state, sku);
                        }
                        case PRODUCT -> {
                            var p = require(state.products, d.targetNo());
                            expected(d.expectedVersion(), p.version());
                            v = p.version() + 1;
                            state.products.put(
                                    d.targetNo(),
                                    new CatalogState.Product(p.draft(), "OFF_SALE", v));
                            for (var sku : List.copyOf(state.skus.values()))
                                if (sku.productNo().equals(d.targetNo())) stopSku(state, sku);
                        }
                        default -> throw new IllegalArgumentException("unknown target");
                    }
                    return new DraftSaveResult(d.targetNo(), null, v);
                });
    }

    private static void stopSku(CatalogState state, CatalogState.Sku sku) {
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
}
