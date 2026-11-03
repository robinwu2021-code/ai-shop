package ai.neargo.commerce.core.catalog;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.PlanTier;

import java.util.*;

/**
 * Internal transaction snapshot. All entries are immutable; repositories persist only changed
 * entries.
 */
public final class CatalogState {
    public record Type(BusinessTypeDraft draft, long catalogVersion, long version) {}

    public record End(BusinessEndDraft draft, long version) {}

    public record Plan(
            String planNo,
            String typeCode,
            PlanTier tier,
            String currentVersionNo,
            String saleStatus,
            long version) {}

    public record PlanVersion(
            PlanDraft draft,
            int revision,
            String status,
            long version,
            Long publishedAt,
            String publishedBy) {}

    public record Product(ProductDraft draft, String saleStatus, long version) {}

    public record Sku(
            String skuNo,
            String productNo,
            String title,
            int maxPerOrder,
            String currentVersionNo,
            String saleStatus,
            long version) {}

    public record SkuVersion(
            SkuDraft draft, int revision, String status, long version, Long publishedAt) {}

    public final Map<String, Type> types = new LinkedHashMap<>();
    public final Map<String, End> ends = new LinkedHashMap<>();
    public final Map<String, Plan> plans = new LinkedHashMap<>();
    public final Map<String, PlanVersion> planVersions = new LinkedHashMap<>();
    public final Map<String, Product> products = new LinkedHashMap<>();
    public final Map<String, Sku> skus = new LinkedHashMap<>();
    public final Map<String, SkuVersion> skuVersions = new LinkedHashMap<>();

    public CatalogState copy() {
        var s = new CatalogState();
        s.types.putAll(types);
        s.ends.putAll(ends);
        s.plans.putAll(plans);
        s.planVersions.putAll(planVersions);
        s.products.putAll(products);
        s.skus.putAll(skus);
        s.skuVersions.putAll(skuVersions);
        return s;
    }
}
