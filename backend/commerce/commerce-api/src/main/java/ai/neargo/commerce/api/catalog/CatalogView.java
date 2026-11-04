package ai.neargo.commerce.api.catalog;

import java.util.List;

/**
 * Operator catalogue includes current display plus all scoped drafts/historical versions and
 * locking tokens.
 */
public record CatalogView(
        String typeCode,
        long catalogVersion,
        boolean enabled,
        List<PlanDraft> plans,
        List<SkuDraft> skus,
        BusinessTypeView businessType,
        List<BusinessEndView> businessEnds,
        List<PlanDefinitionView> planDefinitions,
        List<PlanVersionView> planVersions,
        List<ProductView> products,
        List<SkuDefinitionView> skuDefinitions,
        List<SkuVersionView> skuVersions) {
    public CatalogView {
        plans = List.copyOf(plans);
        skus = List.copyOf(skus);
        businessEnds = List.copyOf(businessEnds);
        planDefinitions = List.copyOf(planDefinitions);
        planVersions = List.copyOf(planVersions);
        products = List.copyOf(products);
        skuDefinitions = List.copyOf(skuDefinitions);
        skuVersions = List.copyOf(skuVersions);
    }
}
