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

    public record Sku(SkuDraft sku, Readiness readiness) {}
}
