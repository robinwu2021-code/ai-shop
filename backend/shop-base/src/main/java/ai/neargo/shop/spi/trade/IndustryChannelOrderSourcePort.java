package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Registered industry owners attest their own immutable source; no generic default-success owner is
 * supplied.
 */
public interface IndustryChannelOrderSourcePort {
    String sourceSystem();

    Facts capture(Reference reference);

    /**
     * Creates an original physical plan for a genuine core preparation generation, never an
     * available-stock estimate.
     */
    Plan demandPlan(Reference reference, String reservationRef, String subOrderNo);

    /**
     * Positive collection facts must not authorize a source already withdrawn or cancelled at this
     * read.
     */
    CollectionFacts collection(Reference reference);

    PlatformAcceptance acceptance(Reference reference);

    Withdrawal withdrawal(Reference reference);

    enum Collection {
        PLATFORM_COLLECTED,
        CASH_ON_DELIVERY,
        UNPAID,
        UNKNOWN
    }

    enum Fulfillment {
        CHANNEL_DELIVERY,
        STORE_PICKUP,
        SHIPMENT
    }

    record Channel(
            String providerCode,
            String profileRef,
            String channelStoreKey,
            String externalAccountRef,
            String externalStoreRef,
            String market) {}

    /**
     * The party is an original source transaction relationship, not a newly invented local customer
     * account.
     */
    record Party(String partyNo, String externalCustomerRef, String displayName) {}

    record Delivery(String receiverName, String phone, String address, String instruction) {}

    record Totals(
            long grossExTaxMinor,
            long merchantDiscountExTaxMinor,
            long taxMinor,
            long merchantChargesMinor,
            long merchantReceivableMinor,
            long platformSubsidyMinor,
            long platformFeesMinor,
            long externalDeliveryFeesMinor,
            long customerPayableMinor) {}

    /**
     * unit is the original owner's canonical physical unit; the external envelope retains its
     * original unit spelling.
     */
    record InvoiceLine(
            String externalLineRef,
            BigDecimal quantity,
            String unit,
            long grossExTaxMinor,
            long merchantDiscountExTaxMinor,
            long taxMinor,
            long netMinor) {}

    /**
     * One source billing component line is one billing unit; physical quantity remains separately
     * exact.
     */
    record Component(
            String externalLineRef,
            String parentExternalLineRef,
            String externalKind,
            String sourceLineRef,
            String componentRef,
            String productionKind,
            String goodsNo,
            String skuNo,
            String title,
            String cover,
            String spec,
            BigDecimal physicalQuantity,
            String unit,
            long grossExTaxMinor,
            long merchantDiscountExTaxMinor,
            long taxMinor,
            long netMinor,
            String allocationBasisRef) {}

    /** sourceHash is calculated over Data, which deliberately does not contain its own hash. */
    record Data(
            Scope scope,
            String sourceSystem,
            String sourceNo,
            long sourceVersion,
            String importRef,
            Channel channel,
            String externalOrderRef,
            String externalDocumentHash,
            String externalReadReceiptRef,
            String externalFinancialHash,
            String currency,
            Collection collection,
            String collectionRef,
            Fulfillment fulfillment,
            Party party,
            Delivery delivery,
            Instant orderedAt,
            List<InvoiceLine> invoiceLines,
            List<Component> components,
            Totals totals) {
        public Data {
            invoiceLines = List.copyOf(invoiceLines);
            components = List.copyOf(components);
        }
    }

    record Facts(
            Reference reference,
            Data data,
            Instant observedAt,
            Instant validUntil,
            String receiptRef) {}

    /**
     * A later payment-state revision can prove collection without silently replacing the immutable
     * invoice or its prices.
     */
    record CollectionFacts(
            Reference reference,
            String externalOrderRef,
            String externalDocumentHash,
            String externalFinancialHash,
            Collection collection,
            Long collectedMinor,
            String currency,
            String originalCollectionRef,
            Instant observedAt,
            Instant validUntil,
            String receiptRef) {}

    /**
     * Platform acceptance proves that upstream state only; acceptedAt is null unless an original
     * event time was supplied.
     */
    record PlatformAcceptance(
            Reference reference,
            String externalOrderRef,
            String externalDocumentHash,
            String externalFinancialHash,
            String platformReceiptRef,
            Instant acceptedAt,
            Instant observedAt,
            Instant validUntil,
            String receiptRef) {}

    record Withdrawal(
            Reference reference,
            String reasonCode,
            String originalWithdrawalRef,
            Instant observedAt,
            Instant validUntil,
            String receiptRef) {}
}
