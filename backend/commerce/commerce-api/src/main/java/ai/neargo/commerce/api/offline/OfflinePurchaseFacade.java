package ai.neargo.commerce.api.offline;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import java.time.Instant;
import java.util.*;
import ai.neargo.commerce.api.fulfillment.FulfillmentQuery;

/** Offline declarations are not funds. Every actor method requires current authoritative authorization. */
public interface OfflinePurchaseFacade {
    record EvidenceTicket(String assetKey, String purpose) {}
    record ClaimCommand(OrderQuery order, String claimNo, Money declaredAmount, Instant declaredPaidAt,
            String bankReference, List<String> evidenceKeys) {
        public ClaimCommand { evidenceKeys = List.copyOf(evidenceKeys); }
    }
    record DeclineCommand(OrderQuery order, String claimNo, String reason) {}
    record ClaimView(String claimNo, String status, Money declaredAmount, Instant declaredPaidAt, Instant submittedAt) {}
    record ReviewCommand(OrderQuery order,String actionNo,String claimNo,Money actualAmount,Instant actualPaidAt,
            String actualReceiver,String bankReference,String evidenceKey,boolean historical,String reason) {}
    record VoidCommand(OrderQuery order,String actionNo,String reconciliationReference,String reason,String evidenceKey,
            Map<String,String> unmatchedDispositions) { public VoidCommand { unmatchedDispositions=Map.copyOf(unmatchedDispositions); } }
    record ActionView(String actionNo,String status,String paymentNo) {}
    ActionView confirm(ActorContext actor,ReviewCommand command);
    ActionView voidOrder(ActorContext actor,VoidCommand command);
    /** Trusted staged recovery consumes saved authorization, never synthesizes a new actor. */
    void recover(FulfillmentQuery query);
    EvidenceTicket reserveEvidence(ActorContext actor, OrderQuery order, String purpose);
    ClaimView submit(ActorContext actor, ClaimCommand command);
    ClaimView decline(ActorContext actor, DeclineCommand command);
    String evidence(ActorContext actor, OrderQuery order, String assetKey);
    /** Trusted media GC references include reservations so upload/commit crash windows retain evidence. */
    List<String> retainedEvidenceKeys(String tenantNo);
}
