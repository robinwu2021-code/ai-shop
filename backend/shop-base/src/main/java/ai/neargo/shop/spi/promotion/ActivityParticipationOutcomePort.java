package ai.neargo.shop.spi.promotion;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;

/** Only the source owner may attest intent bindings and durable terminal outcomes. */
public interface ActivityParticipationOutcomePort {
    boolean supports(SourceRef source);
    void validateSource(EntitlementContext context, ActivityParticipationPort.ReserveActivity command);
    Outcome outcome(EntitlementContext context, SourceRef source);
    enum State { UNKNOWN, COMMITTED, ABORTED }
    record Outcome(State state, String factNo, String tenantNo, ActivityParticipationPort.ReserveActivity binding) { }
}
