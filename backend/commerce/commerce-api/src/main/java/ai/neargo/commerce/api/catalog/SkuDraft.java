package ai.neargo.commerce.api.catalog;

import java.util.List;
import ai.neargo.commerce.api.common.*;

public record SkuDraft(String skuNo, String title, Money price, List<SkuComponent> components, long expectedVersion) {}
