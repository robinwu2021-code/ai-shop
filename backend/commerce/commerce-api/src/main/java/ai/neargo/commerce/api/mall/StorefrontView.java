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
        List<Product> products) {
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
