package ai.neargo.commerce.api.catalog;

import java.util.List;
import ai.neargo.commerce.api.common.*;

public record CatalogView(String typeCode, long catalogVersion, List<PlanDraft> plans, List<SkuDraft> skus) {}
