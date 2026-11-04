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

    Receipt refund(Request request);

    Receipt query(Key key);

    Optional<Key> refundKey(String paymentNoOrOutRefundNo);
}
