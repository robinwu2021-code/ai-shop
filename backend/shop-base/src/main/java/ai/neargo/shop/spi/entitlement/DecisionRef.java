package ai.neargo.shop.spi.entitlement;

import java.time.Instant;

/** A durable source decision reference, not a signature or proof of authorization by itself. */
public record DecisionRef(String decisionNo, Instant occurredAt, long sourceRevision, DecisionType decisionType) {
    public DecisionRef {
        ContractChecks.id(decisionNo);
        ContractChecks.present(occurredAt);
        ContractChecks.present(decisionType);
        ContractChecks.require(sourceRevision >= 0);
    }

    public enum DecisionType {
        GRANT_COMMIT, GRANT_ABORT, USE_COMMIT, USE_CANCEL, ORDER_REVERSE, RECHARGE_RECEIVED
    }

    /** Reject revision substitution and using a success decision as a cancellation. */
    public void requireFor(SourceRef source, DecisionType expectedType) {
        ContractChecks.present(source);
        ContractChecks.require(sourceRevision == source.sourceRevision() && decisionType == expectedType);
    }
}
