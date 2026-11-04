package ai.neargo.shop.pay.service;

import java.util.Optional;

/** Platform refund intents share the original stl_payment cash-capacity lock and ledger. */
public interface PlatformRefundService {
    record Key(String tenantNo, String orderNo, String refundNo) {}

    record Request(
            Key key,
            String storeNo,
            String originalAttemptNo,
            String originalPaymentNo,
            String merchantNo,
            long originalPaidMinor,
            long refundMinor,
            String currency,
            String reason) {}

    record Receipt(
            String paymentNo,
            Request request,
            String state,
            String providerNo,
            Long verifiedAt,
            String errorCode) {}

    /**
     * Actual bank payout for a refund whose original was collected OFFLINE. Nothing is transferred
     * by the system; the operator records the real outgoing transfer. Amount must equal the frozen
     * refund intent; the bank reference is unique across all platform bank facts.
     */
    record OfflinePayout(Key key, String operatorNo, long actualMinor, String currency, long paidOutAt,
            String payeeReference, String bankReference, String evidenceReference, long confirmedAt) {}
    default Receipt confirmOfflinePayout(OfflinePayout payout) { throw new UnsupportedOperationException(); }

    Receipt refund(Request request);

    Receipt query(Key key);

    Optional<Key> refundKey(String paymentNoOrOutRefundNo);
}
