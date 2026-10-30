package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.trade.order.service.create.CounterEconomicCodec.CounterPolicy;

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
        String purchaseAuthority,
        CounterPolicy counterPolicy,
        CheckoutEconomicSnapshot originalCounterEconomic) {
    /** Existing callers keep their previous pricing lane while the sealed counter integration is staged. */
    public CheckoutPricingScope(
            String tenantNo, String entityNo, String storeNo, long scopeRevision, String scopeHash,
            List<CheckoutLineReq> lines, StoreSalesCatalogPort.Snapshot catalog, String admissionKind,
            String operationNo, CustomerParty party, String purchaseAuthority) {
        this(tenantNo, entityNo, storeNo, scopeRevision, scopeHash, lines, catalog, admissionKind, operationNo,
                party, purchaseAuthority, null, null);
    }

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
        boolean counterLane = "MERCHANT_RECORDED_IN_PERSON".equals(purchaseAuthority);
        if (!counterLane && (counterPolicy != null || originalCounterEconomic != null)
                || counterPolicy == null && originalCounterEconomic != null
                || counterPolicy == CounterPolicy.FROZEN_M2A_ZERO
                        && (originalCounterEconomic == null || originalCounterEconomic.counterActivitySeal() != null)
                || counterPolicy == CounterPolicy.CURRENT_FULL_CUT && originalCounterEconomic != null
                        && originalCounterEconomic.counterActivitySeal() == null) {
            throw new IllegalArgumentException("invalid trusted counter pricing policy");
        }
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
