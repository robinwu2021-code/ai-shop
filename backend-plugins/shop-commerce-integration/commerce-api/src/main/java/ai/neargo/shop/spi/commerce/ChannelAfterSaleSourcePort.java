package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;

import java.time.Instant;

/**
 * A real original core decision is required; supplier snapshots and caller amounts cannot authorize
 * it.
 */
public interface ChannelAfterSaleSourcePort {
    ChannelSourceValidationPort.Owner owner();

    Facts capture(Query query);

    record Query(
            String entityNo,
            String storeNo,
            String coreOrderNo,
            String externalCaseRef,
            String sourceDecisionRef,
            String sourceRevision) {}

    record Facts(
            Query query,
            IndustryChannelAfterSalePort.Decision decision,
            String decisionHash,
            Instant observedAt,
            Instant validUntil,
            String verificationReceiptRef,
            String localFundsStatus,
            String localInventoryStatus) {}
}
