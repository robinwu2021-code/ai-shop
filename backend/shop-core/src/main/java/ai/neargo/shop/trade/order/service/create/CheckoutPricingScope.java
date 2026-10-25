package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;

import java.util.List;

/** Internal owner-resolved pricing lane; no public request can set admissionKind. */
public record CheckoutPricingScope(
        String tenantNo,
        String entityNo,
        String storeNo,
        long scopeRevision,
        String scopeHash,
        List<CheckoutLineReq> lines,
        StoreSalesCatalogPort.Snapshot catalog,
        String admissionKind,
        String operationNo) {
    public CheckoutPricingScope(
            String tenantNo,
            String entityNo,
            String storeNo,
            long scopeRevision,
            String scopeHash,
            List<CheckoutLineReq> lines,
            StoreSalesCatalogPort.Snapshot catalog) {
        this(
                tenantNo,
                entityNo,
                storeNo,
                scopeRevision,
                scopeHash,
                lines,
                catalog,
                "LEGACY_FUNDED",
                null);
    }
    public CheckoutPricingScope {
        lines = List.copyOf(lines);
        if (!List.of("LEGACY_FUNDED", "MANUAL_OFFLINE").contains(admissionKind)) {
            throw new IllegalArgumentException("invalid pricing admission lane");
        }
    }

    public boolean manual() {
        return "MANUAL_OFFLINE".equals(admissionKind);
    }
}
