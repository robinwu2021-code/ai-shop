package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.CheckoutReadinessPort.Readiness;

import java.util.List;

/** Only current published SKUs; no operator drafts or historical catalog configuration. */
public record StorefrontView(
        StoreRef store,
        String businessTypeCode,
        String currency,
        long catalogVersion,
        boolean canProcure,
        List<Product> products,
        List<Category> categories) {
    public StorefrontView {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public StorefrontView(
            StoreRef store,
            String businessTypeCode,
            String currency,
            long catalogVersion,
            boolean canProcure,
            List<Product> products) {
        this(store, businessTypeCode, currency, catalogVersion, canProcure, products, List.of());
    }

    /**
     * Enabled categories used by the visible products, plus all their ancestors, ordered by
     * (level, sortOrder, code).
     */
    public record Category(
            String code,
            String parentCode,
            int level,
            java.util.Map<String, String> names,
            String icon,
            int sortOrder) {}

    public record Product(ProductDraft product, List<Sku> skus) {}

    /**
     * {@code readiness} is online payment only. {@code offlineReadiness} is null unless this
     * deployment publishes offline bank transfer terms; then it says whether the SKU can be bought
     * by transfer (same shape, OFFLINE/BANK_TRANSFER option).
     */
    public record Sku(SkuDraft sku, Readiness readiness, Readiness offlineReadiness) {
        public Sku(SkuDraft sku, Readiness readiness) {
            this(sku, readiness, null);
        }
    }
}
