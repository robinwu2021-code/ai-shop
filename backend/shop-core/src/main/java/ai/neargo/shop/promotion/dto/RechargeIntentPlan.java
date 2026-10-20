package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort.SourceFact;
import java.time.Instant;

/** Permit may be null only inside the source/reservation transaction. No raw member reference is persisted. */
public record RechargeIntentPlan(String intentNo, EntitlementContext context, SourceRef source, String rechargeNo,
                                 ReserveActivity participation, ParticipationPermit permit, String storeName,
                                 Instant createdAt, Instant expiresAt) {
    public SourceFact fact() {
        return new SourceFact(context, source, rechargeNo, participation, permit, storeName, createdAt, expiresAt);
    }
    public RechargeIntentPlan permitted(ParticipationPermit value) {
        return new RechargeIntentPlan(intentNo, context, source, rechargeNo, participation, value, storeName,
                createdAt, expiresAt);
    }
}
