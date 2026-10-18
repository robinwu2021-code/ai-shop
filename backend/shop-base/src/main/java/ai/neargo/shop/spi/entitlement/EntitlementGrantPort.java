package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import java.util.List;

/** Payment-owned issuance protocol. Preparing capacity and issuing rights are separate durable transitions. */
public interface EntitlementGrantPort {
    GrantView prepare(EntitlementContext context, GrantCommand command);

    GrantView commit(EntitlementContext context, GrantDecision command);

    GrantView abort(EntitlementContext context, AbortGrant command);

    /** Lookup the original operation, including when a previous response was lost. */
    GrantView query(EntitlementContext context, String entityNo, String grantNo);

    enum State { NOT_FOUND, PREPARED, COMMITTED, ABORTED, PROCESSING, EXCEPTION }

    record GrantCommand(String grantNo, SourceRef sourceRef, ParticipationPermit permit, String templateVersionNo,
                        String recipientUserNo, String entityNo, int quantity, String benefitSnapshotHash,
                        String paidFactRef) {
        public GrantCommand {
            ContractChecks.id(grantNo);
            ContractChecks.present(sourceRef);
            ContractChecks.id(templateVersionNo);
            ContractChecks.id(recipientUserNo);
            ContractChecks.id(entityNo);
            ContractChecks.require(quantity > 0);
            ContractChecks.hash(benefitSnapshotHash);
            ContractChecks.optionalId(paidFactRef);
            if ("PAID_ORDER".equals(sourceRef.sourceType())) {
                ContractChecks.id(paidFactRef);
                ContractChecks.require(sourceRef.sourceLineNo() != null && sourceRef.unitIndex() != null
                        && sourceRef.entitlementIndex() != null && quantity == 1 && permit == null);
            }
            if (permit != null) {
                ContractChecks.require(permit.sourceRef().equals(sourceRef)
                        && permit.userNo().equals(recipientUserNo) && permit.entityNo().equals(entityNo));
            }
        }
    }

    record GrantDecision(String grantNo, SourceRef sourceRef, DecisionRef decisionRef) {
        public GrantDecision {
            ContractChecks.id(grantNo);
            ContractChecks.present(decisionRef).requireFor(sourceRef, DecisionRef.DecisionType.GRANT_COMMIT);
        }
    }

    record AbortGrant(String grantNo, SourceRef sourceRef, DecisionRef decisionRef) {
        public AbortGrant {
            ContractChecks.id(grantNo);
            ContractChecks.present(decisionRef).requireFor(sourceRef, DecisionRef.DecisionType.GRANT_ABORT);
        }
    }

    record GrantView(String grantNo, State status, List<String> instanceNos, SourceRef sourceRef) {
        public GrantView {
            ContractChecks.id(grantNo);
            ContractChecks.present(status);
            instanceNos = List.copyOf(ContractChecks.present(instanceNos));
            instanceNos.forEach(ContractChecks::id);
            if (status == State.COMMITTED) {
                ContractChecks.require(!instanceNos.isEmpty());
            } else {
                ContractChecks.require(instanceNos.isEmpty());
            }
        }
    }
}
