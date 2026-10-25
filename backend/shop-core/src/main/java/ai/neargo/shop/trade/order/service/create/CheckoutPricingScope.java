package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
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
        String operationNo,
        CustomerParty party,
        String purchaseAuthority) {
    public CheckoutPricingScope(
            String tenantNo,
            String entityNo,
            String storeNo,
            long scopeRevision,
            String scopeHash,
            List<CheckoutLineReq> lines,
            StoreSalesCatalogPort.Snapshot catalog,
            String admissionKind,
            String operationNo) {
        this(
                tenantNo,
                entityNo,
                storeNo,
                scopeRevision,
                scopeHash,
                lines,
                catalog,
                admissionKind,
                operationNo,
                null,
                "CONSUMER_SESSION");
    }

    public boolean counter() {
        return "MERCHANT_RECORDED_IN_PERSON".equals(purchaseAuthority);
    }

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
        if ("MERCHANT_RECORDED_IN_PERSON".equals(purchaseAuthority)
                ? party == null || !"MANUAL_OFFLINE".equals(admissionKind)
                : party != null || !"CONSUMER_SESSION".equals(purchaseAuthority)) {
            throw new IllegalArgumentException("invalid pricing purchase authority");
        }
        if (!List.of("LEGACY_FUNDED", "MANUAL_OFFLINE").contains(admissionKind)) {
            throw new IllegalArgumentException("invalid pricing admission lane");
        }
    }

    public boolean manual() {
        return "MANUAL_OFFLINE".equals(admissionKind);
    }
}
