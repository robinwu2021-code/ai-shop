package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.*;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;

/** Actual durable HOST route authority, separate from current HUMAN SELF and saved actor provenance. */
public interface ExecutionStockRetentionIntentPort {
    AcceptedComponentAuthority queryAcceptedComponent(AcceptedComponentLookup request);
    String PURPOSE = "CONTINUE_ACCEPTED_EXECUTION_STOCK_RETENTION";

    record AcceptedComponentLookup(RetentionLookup original, String acceptedIntentNo, String acceptedIntentHash,
            ComponentKind ownerKind, String componentNo, String componentHash) { }
    record AcceptedComponentAuthority(String schema, String purpose, AcceptedComponentLookup target,
            Outcome outcome, String reason, RouteOriginalV2 route, long revision, long observationEpoch,
            String authorityHash) {
        public AcceptedComponentAuthority signed() {
            return new AcceptedComponentAuthority(schema, purpose, target, outcome, reason, route, revision,
                    observationEpoch, ExecutionEvidenceContract.digest(schema, this, "authorityHash"));
        }
        public void validate(AcceptedComponentLookup expected) {
            ExecutionEvidenceContract.require("EXECUTION_STOCK_ACCEPTED_COMPONENT_AUTHORITY_V2".equals(schema)
                    && PURPOSE.equals(purpose) && target.equals(expected) && outcome == Outcome.RETAINED
                    && equals(signed()) && revision >= 0 && observationEpoch >= 0 && route != null);
            route.validate();
            ExecutionEvidenceContract.require(route.original().equals(target.original())
                    && route.acceptedIntentNo().equals(target.acceptedIntentNo())
                    && route.acceptedIntentHash().equals(target.acceptedIntentHash())
                    && route.components().stream().anyMatch(c -> c.componentKind() == target.ownerKind()
                            && c.applicability() == Applicability.REQUIRED
                                && c.componentNo().equals(target.componentNo())
                            && c.componentHash().equals(target.componentHash())));
        }
    }
}
