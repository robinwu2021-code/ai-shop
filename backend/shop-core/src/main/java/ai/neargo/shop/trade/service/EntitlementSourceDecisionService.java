package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;

/** Called by the core source owner inside the same transaction as its terminal business decision. */
public interface EntitlementSourceDecisionService {
    DecisionRef record(String tenantNo, SourceRef source, String purpose, DecisionRef.DecisionType type,
                       String authorizationRef, String paidFactRef, String userNo, String entityNo, String storeNo,
                       String currency, long amountMinor, String snapshotHash, Instant occurredAt);
}
