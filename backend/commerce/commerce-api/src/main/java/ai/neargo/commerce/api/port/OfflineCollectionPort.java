package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.Money;
import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import java.time.Instant;
import java.util.*;

/** Trusted pay-domain manual rail. All calls occur outside commerce transactions; no bank/provider IO. */
public interface OfflineCollectionPort {
    record ManualPayment(PaymentIntentCommand original,String operatorNo,Money actualAmount,Instant actualPaidAt,
            String actualReceiver,String bankReference,String evidenceReference,boolean historical,Instant confirmedAt,
            String collectorNo, String collectionReference,boolean testPayment) {
        public ManualPayment(PaymentIntentCommand original,String operatorNo,Money actualAmount,Instant actualPaidAt,
                String actualReceiver,String bankReference,String evidenceReference,boolean historical,Instant confirmedAt,
                String collectorNo,String collectionReference) {
            this(original,operatorNo,actualAmount,actualPaidAt,actualReceiver,bankReference,evidenceReference,historical,confirmedAt,collectorNo,collectionReference,false);
        }
        public ManualPayment(PaymentIntentCommand original,String operatorNo,Money actualAmount,Instant actualPaidAt,
                String actualReceiver,String bankReference,String evidenceReference,boolean historical,Instant confirmedAt) {
            this(original,operatorNo,actualAmount,actualPaidAt,actualReceiver,bankReference,evidenceReference,historical,confirmedAt,null,null,false);
        }
    }
    record Collector(String collectorNo, String displayName) {}
    default List<Collector> collectors(ai.neargo.commerce.api.common.StoreRef store) { return List.of(); }
    record ManualResult(String disposition,String paymentNo) {}
    record Reconciliation(PaymentIntentCommand original,String operatorNo,String reference,String reason,
            String evidenceReference,Instant reconciledAt,Map<String,String> unmatchedDispositions) {}
    /** Operator-recorded real outgoing transfer for a refund of an OFFLINE-collected order. */
    record RefundPayout(String tenantNo,String orderNo,String refundNo,String operatorNo,Money actualAmount,
            Instant paidOutAt,String payeeReference,String bankReference,String evidenceReference,Instant confirmedAt) {}
    /** Verified real cash only. refundedMinor includes successful verified refunds; pending reserves capacity. */
    record CashReceipt(String paymentNo,String paymentAttemptNo,String platformMerchantNo,Money amount,
            long refundedMinor,boolean pendingRefund,boolean exceptionCash,boolean closed) {}
    /** Read outside caller transactions; never expose receipt keys of another tenant/order. */
    default List<CashReceipt> receipts(String tenant,String orderNo) { throw new UnsupportedOperationException(); }
    default void payout(RefundPayout payout) { throw new UnsupportedOperationException(); }
    default OfflineCollectionTerms configure(String tenant, String operatorNo,
            ai.neargo.commerce.api.offline.OfflinePurchaseFacade.ProfileCommand command) {
        throw new UnsupportedOperationException();
    }
    default Optional<ai.neargo.commerce.api.offline.OfflineCollectionProfile> profile(String tenant,String profile,String currency) { throw new UnsupportedOperationException(); }
    default Optional<OfflineCollectionTerms> terms(String tenant,String profile,String currency) { return Optional.empty(); }
    default ManualResult confirm(ManualPayment command) { throw new UnsupportedOperationException(); }
    default void reconcile(Reconciliation command) { throw new UnsupportedOperationException(); }
}
