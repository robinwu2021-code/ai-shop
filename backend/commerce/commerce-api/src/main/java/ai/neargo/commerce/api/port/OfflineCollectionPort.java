package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.Money;
import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import java.time.Instant;
import java.util.*;

/** Trusted pay-domain manual rail. All calls occur outside commerce transactions; no bank/provider IO. */
public interface OfflineCollectionPort {
    record ManualPayment(PaymentIntentCommand original,String operatorNo,Money actualAmount,Instant actualPaidAt,
            String actualReceiver,String bankReference,String evidenceReference,boolean historical,Instant confirmedAt) {}
    record ManualResult(String disposition,String paymentNo) {}
    record Reconciliation(PaymentIntentCommand original,String operatorNo,String reference,String reason,
            String evidenceReference,Instant reconciledAt,Map<String,String> unmatchedDispositions) {}
    default Optional<OfflineCollectionTerms> terms(String tenant,String profile,String currency) { return Optional.empty(); }
    default ManualResult confirm(ManualPayment command) { throw new UnsupportedOperationException(); }
    default void reconcile(Reconciliation command) { throw new UnsupportedOperationException(); }
}
