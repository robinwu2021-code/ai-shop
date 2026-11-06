package ai.neargo.commerce.api.catalog;

import java.util.List;

/** Operator bootstrap view, valid even before the first business type exists. */
public record CatalogOverview(
        List<BusinessTypeView> businessTypes,
        List<BusinessEndView> businessEnds,
        List<PlanDefinitionView> planDefinitions,
        List<PlanVersionView> planVersions,
        List<ProductView> products,
        List<SkuDefinitionView> skuDefinitions,
        List<SkuVersionView> skuVersions,
        List<CategoryView> categories) {
    public CatalogOverview {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public CatalogOverview(
            List<BusinessTypeView> businessTypes,
            List<BusinessEndView> businessEnds,
            List<PlanDefinitionView> planDefinitions,
            List<PlanVersionView> planVersions,
            List<ProductView> products,
            List<SkuDefinitionView> skuDefinitions,
            List<SkuVersionView> skuVersions) {
        this(
                businessTypes,
                businessEnds,
                planDefinitions,
                planVersions,
                products,
                skuDefinitions,
                skuVersions,
                List.of());
    }
}
