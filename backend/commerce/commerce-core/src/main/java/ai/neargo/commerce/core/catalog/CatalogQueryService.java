package ai.neargo.commerce.core.catalog;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.ActorContext;

/** Read projection exposes authoritative locking tokens separately from immutable contract data. */
final class CatalogQueryService {
    private final CatalogSupport support;

    CatalogQueryService(CatalogSupport support) {
        this.support = support;
    }

    java.util.List<BusinessEndDeploymentOption> deploymentOptions(ActorContext actor) {
        support.authorize(actor,"read");
        return java.util.List.copyOf(support.readiness.acceptedDeployments(actor.tenantNo()));
    }

    CatalogOverview overview(ActorContext actor) {
        var s = support.read(actor);
        return new CatalogOverview(
                s.types.values().stream().map(t -> new BusinessTypeView(t.draft(), t.version())).toList(),
                s.ends.values().stream().map(e -> new BusinessEndView(e.draft(), e.version())).toList(),
                s.plans.values().stream().map(p -> new PlanDefinitionView(p.planNo(),p.typeCode(),p.tier(),p.currentVersionNo(),p.saleStatus(),p.version())).toList(),
                s.planVersions.values().stream().map(p -> new PlanVersionView(p.draft(),p.status(),p.revision(),p.version())).toList(),
                s.products.values().stream().map(p -> new ProductView(p.draft(),p.saleStatus(),p.version())).toList(),
                s.skus.values().stream().map(v -> new SkuDefinitionView(v.skuNo(),v.productNo(),v.title(),v.maxPerOrder(),v.currentVersionNo(),v.saleStatus(),v.version())).toList(),
                s.skuVersions.values().stream().map(v -> new SkuVersionView(v.draft(),v.status(),v.revision(),v.version())).toList());
    }

    CatalogView catalog(ActorContext actor, String type) {
        var state = support.read(actor);
        var businessType = CatalogSupport.require(state.types, type);
        var planDefinitions =
                state.plans.values().stream()
                        .filter(p -> p.typeCode().equals(type))
                        .map(
                                p ->
                                        new PlanDefinitionView(
                                                p.planNo(),
                                                p.typeCode(),
                                                p.tier(),
                                                p.currentVersionNo(),
                                                p.saleStatus(),
                                                p.version()))
                        .toList();
        var planVersions =
                state.planVersions.values().stream()
                        .filter(p -> p.draft().typeCode().equals(type))
                        .map(
                                p ->
                                        new PlanVersionView(
                                                p.draft(), p.status(), p.revision(), p.version()))
                        .toList();
        var products =
                state.products.values().stream()
                        .filter(p -> p.draft().allowedBusinessTypes().contains(type))
                        .map(p -> new ProductView(p.draft(), p.saleStatus(), p.version()))
                        .toList();
        var productNos = products.stream().map(p -> p.draft().productNo()).toList();
        var skuDefinitions =
                state.skus.values().stream()
                        .filter(s -> productNos.contains(s.productNo()))
                        .map(
                                s ->
                                        new SkuDefinitionView(
                                                s.skuNo(),
                                                s.productNo(),
                                                s.title(),
                                                s.maxPerOrder(),
                                                s.currentVersionNo(),
                                                s.saleStatus(),
                                                s.version()))
                        .toList();
        var skuVersions =
                state.skuVersions.values().stream()
                        .filter(s -> s.draft().allowedBusinessTypes().contains(type))
                        .map(
                                s ->
                                        new SkuVersionView(
                                                s.draft(), s.status(), s.revision(), s.version()))
                        .toList();
        var businessEnds =
                state.ends.values().stream()
                        .filter(e -> e.draft().allowedBusinessTypes().contains(type))
                        .map(e -> new BusinessEndView(e.draft(), e.version()))
                        .toList();
        var plans =
                state.plans.values().stream()
                        .filter(p -> p.typeCode().equals(type) && p.currentVersionNo() != null)
                        .map(p -> state.planVersions.get(p.currentVersionNo()).draft())
                        .toList();
        var skus =
                state.skus.values().stream()
                        .filter(
                                s ->
                                        "ON_SALE".equals(s.saleStatus())
                                                && "ON_SALE"
                                                        .equals(
                                                                state.products
                                                                        .get(s.productNo())
                                                                        .saleStatus()))
                        .map(s -> state.skuVersions.get(s.currentVersionNo()))
                        .filter(v -> v != null && "PUBLISHED".equals(v.status()))
                        .map(CatalogState.SkuVersion::draft)
                        .filter(
                                s ->
                                        s.title() != null
                                                && s.maxPerOrder() > 0
                                                && s.allowedBusinessTypes().contains(type))
                        .toList();
        return new CatalogView(
                type,
                businessType.catalogVersion(),
                businessType.draft().enabled(),
                plans,
                skus,
                new BusinessTypeView(businessType.draft(), businessType.version()),
                businessEnds,
                planDefinitions,
                planVersions,
                products,
                skuDefinitions,
                skuVersions);
    }
}
