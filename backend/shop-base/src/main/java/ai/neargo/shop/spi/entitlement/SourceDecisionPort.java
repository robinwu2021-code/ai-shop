package ai.neargo.shop.spi.entitlement;

import java.time.Instant;

/** Source-owner queries used after lost responses; a reference supplied by a caller is never sufficient proof. */
public interface SourceDecisionPort {
    /** Return durable source truth; UNKNOWN/PENDING must not be interpreted as cancellation. */
    SourceDecision query(EntitlementContext context, SourceRef source);

    /** Resolve a durable owner-confirmed intent and its bound source, selection and expiry. */
    AuthorizationDecision authorization(EntitlementContext context, String authorizationRef);

    enum State { UNKNOWN, PENDING, COMMITTED, CANCELLED }

    enum AuthorizationState { UNKNOWN, AUTHORIZED, CANCELLED, EXPIRED }

    record SourceDecision(SourceRef source, State status, DecisionRef decision, String userNo, String entityNo,
                          String storeNo, String currency, Long amountMinor, String snapshotHash) {
        public SourceDecision {
            ContractChecks.present(source);
            ContractChecks.present(status);
            if (status == State.UNKNOWN || status == State.PENDING) {
                ContractChecks.require(decision == null);
                if (status == State.UNKNOWN) {
                    ContractChecks.require(userNo == null && entityNo == null && storeNo == null && currency == null
                            && amountMinor == null && snapshotHash == null);
                }
            } else {
                ContractChecks.present(decision);
                ContractChecks.require(decision.sourceRevision() == source.sourceRevision());
                boolean cancelled = decision.decisionType() == DecisionRef.DecisionType.GRANT_ABORT
                        || decision.decisionType() == DecisionRef.DecisionType.USE_CANCEL;
                ContractChecks.require((status == State.CANCELLED) == cancelled);
                ContractChecks.id(userNo);
                ContractChecks.id(entityNo);
                ContractChecks.optionalId(storeNo);
                ContractChecks.currency(currency);
                ContractChecks.require(amountMinor != null && amountMinor >= 0);
                ContractChecks.hash(snapshotHash);
            }
        }
    }

    record AuthorizationDecision(String authorizationRef, AuthorizationState status, SourceRef source,
                                 String userNo, String entityNo, String storeNo, String currency,
                                 Long amountMinor, String selectionDigest, Instant expiresAt) {
        public AuthorizationDecision {
            ContractChecks.id(authorizationRef);
            ContractChecks.present(status);
            if (status != AuthorizationState.UNKNOWN) {
                ContractChecks.present(source);
                ContractChecks.id(userNo);
                ContractChecks.id(entityNo);
                ContractChecks.id(storeNo);
                ContractChecks.currency(currency);
                ContractChecks.require(amountMinor != null && amountMinor >= 0);
                ContractChecks.hash(selectionDigest);
                ContractChecks.present(expiresAt);
            }
        }
    }
}
