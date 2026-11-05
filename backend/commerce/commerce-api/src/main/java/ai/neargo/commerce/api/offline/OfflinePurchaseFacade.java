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
        /** Null-tolerant copy: the facade validates and returns INVALID_REQUEST, never an NPE. */
        public ClaimCommand { evidenceKeys = evidenceKeys == null ? null : Collections.unmodifiableList(new ArrayList<>(evidenceKeys)); }
    }
    record DeclineCommand(OrderQuery order, String claimNo, String reason) {}
    /**
     * A buyer declaration as submitted. bankReference and evidenceKeys are the buyer's own words and receipts:
     * operators use them to find the transfer on the bank statement, never as proof of receipt.
     * {@code receivedAmount}: what the platform actually recorded at review (CONFIRMED/MISMATCHED),
     * else null. {@code reviewNote}: the reviewer's reason shown to the buyer (DECLINED/MISMATCHED),
     * else null. Operator evidence and receiver are never exposed here.
     */
    record ClaimView(String claimNo, String status, Money declaredAmount, Instant declaredPaidAt, Instant submittedAt,
            String bankReference, List<String> evidenceKeys, Money receivedAmount, String reviewNote) {
        public ClaimView { evidenceKeys = evidenceKeys == null ? List.of() : List.copyOf(evidenceKeys); }
        public ClaimView(String claimNo, String status, Money declaredAmount, Instant declaredPaidAt, Instant submittedAt,
                String bankReference, List<String> evidenceKeys) {
            this(claimNo, status, declaredAmount, declaredPaidAt, submittedAt, bankReference, evidenceKeys, null, null);
        }
    }
    record ReviewCommand(OrderQuery order,String actionNo,String claimNo,Money actualAmount,Instant actualPaidAt,
            String actualReceiver,String bankReference,String evidenceKey,boolean historical,String reason) {}
    record VoidCommand(OrderQuery order,String actionNo,String reconciliationReference,String reason,String evidenceKey,
            Map<String,String> unmatchedDispositions) {
        public VoidCommand { unmatchedDispositions = unmatchedDispositions == null ? null
                : Collections.unmodifiableMap(new TreeMap<>(unmatchedDispositions)); }
    }
    record ActionView(String actionNo,String status,String paymentNo) {}
    /**
     * Offline quote: collection terms come only from the trusted pay-domain profile configured for this deployment,
     * never from the caller. A merchant ACCOUNT quotes for itself; an OPERATOR quotes as assisted STORE purchase.
     */
    QuoteView quote(ActorContext actor, QuoteCommand command);
    record RefundPayoutCommand(OrderQuery order,String refundNo,Money actualAmount,Instant paidOutAt,
            String payeeReference,String bankReference,String evidenceKey) {}
    /**
     * Records the real bank payout of an approved refund on an OFFLINE order. The system never
     * transfers money; the refund completes only after the ledger accepts this proof.
     */
    RefundView confirmRefundPayout(ActorContext actor, RefundPayoutCommand command);
    /**
     * Bank instructions frozen into this order; disclosed only after durable issuance. When cash is
     * payable they also carry {@code transferRemark}: the order number (letters and digits only) the
     * buyer writes on the transfer so the operator can find the order when confirming receipt.
     */
    record InstructionsView(String orderNo, Money payable, Instant paymentDueAt, String profileNo,
            long profileVersion, Map<String, String> instructions) {}
    /** Issues (idempotently) the frozen instructions for an offline order and returns them. */
    InstructionsView issue(ActorContext actor, OrderQuery order);
    InstructionsView instructions(ActorContext actor, OrderQuery order);
    /** Merchants see their own declarations; operators see all declarations on the order. */
    List<ClaimView> claims(ActorContext actor, OrderQuery order);
    record PendingClaim(StoreRef store, String orderNo, ClaimView claim) {}
    /** Operator review queue of SUBMITTED declarations, keyset-paged by (orderNo, claimNo). */
    List<PendingClaim> pendingClaims(ActorContext actor, String afterOrderNo, String afterClaimNo, int limit);
    record ProfileCommand(String profileNo, long expectedVersion, String platformMerchantNo, String currency,
            String bankCode, String bankAccount, String accountName, String paymentWindowVersion,
            long paymentWindowMs, boolean enabled, String reason) {}
    /** Pay-domain collection profile; each change creates a new immutable version. */
    OfflineCollectionTerms configureProfile(ActorContext actor, ProfileCommand command);
    Optional<OfflineCollectionTerms> profile(ActorContext actor, String currency);
    ActionView confirm(ActorContext actor,ReviewCommand command);
    ActionView voidOrder(ActorContext actor,VoidCommand command);
    /** Trusted staged recovery consumes saved authorization, never synthesizes a new actor. */
    void recover(FulfillmentQuery query);
    record Sweep(int visited, int failed, String nextOrderNo) {}
    /** Trusted scheduler: bounded scan of orders with ACCEPTED actions, resuming after {@code afterOrderNo}. */
    Sweep recoverPending(String tenantNo, String afterOrderNo, int limit);
    EvidenceTicket reserveEvidence(ActorContext actor, OrderQuery order, String purpose);
    /** Uploads bytes once for the caller's own reservation; stored evidence is immutable. */
    void upload(ActorContext actor, OrderQuery order, String assetKey, ai.neargo.commerce.api.port.PrivateEvidencePort.Upload upload);
    ClaimView submit(ActorContext actor, ClaimCommand command);
    ClaimView decline(ActorContext actor, DeclineCommand command);
    String evidence(ActorContext actor, OrderQuery order, String assetKey);
    /** Trusted media GC references include reservations so upload/commit crash windows retain evidence. */
    List<String> retainedEvidenceKeys(String tenantNo);
}
