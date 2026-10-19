package ai.neargo.shop.spi.entitlement;

/** At-least-once pay source event handoff; acceptance does not imply business subscribers have finished. */
public final class EntitlementEventInternal {
    private EntitlementEventInternal() { }
    public static final String RECEIVE = "/internal/core/entitlement/events/receive";
    public record Envelope(String tenantNo, String eventNo, String eventType, int schemaVersion,
                           String aggregateType, String aggregateNo, String payload, String payloadHash,
                           long occurredAt) {
        public Envelope {
            ContractChecks.id(tenantNo); ContractChecks.id(eventNo); ContractChecks.id(eventType);
            ContractChecks.id(aggregateType); ContractChecks.id(aggregateNo); ContractChecks.hash(payloadHash);
            ContractChecks.require(schemaVersion == 1 && occurredAt > 0 && payload != null && !payload.isBlank());
        }
        @Override public String toString() {
            return "Envelope[eventNo=" + eventNo + ", eventType=" + eventType + ", payload=REDACTED]";
        }
    }
    public record Accepted(String eventNo, boolean accepted) {
        public Accepted { ContractChecks.id(eventNo); ContractChecks.require(accepted); }
    }
}
