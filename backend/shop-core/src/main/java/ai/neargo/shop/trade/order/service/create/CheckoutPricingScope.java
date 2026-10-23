package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;

import java.util.List;

public record CheckoutPricingScope(
        String tenantNo,
        String entityNo,
        String storeNo,
        long scopeRevision,
        String scopeHash,
        List<CheckoutLineReq> lines,
        StoreSalesCatalogPort.Snapshot catalog) {
    public CheckoutPricingScope {
        lines = List.copyOf(lines);
    }
}
