package ai.neargo.shop.spi.commerce;

import java.time.Instant;

/**
 * Independent funds owner authorizes the exact external refund action; no default or supplier-paid
 * shortcut exists.
 */
public interface ChannelAfterSaleFundsPort {
    Facts capture(Query query);

    enum Status {
        AUTHORIZED,
        WAITING,
        REJECTED
    }

    record Query(
            String entityNo,
            String storeNo,
            String coreOrderNo,
            String externalCaseRef,
            String sourceDecisionRef,
            String sourceRevision,
            String supplierCaseHash,
            String currency,
            long merchantRefundMinor) {}

    record Facts(
            Query query,
            Status status,
            String originalAuthorizationRef,
            Instant observedAt,
            Instant validUntil,
            String receiptRef) {}
}
