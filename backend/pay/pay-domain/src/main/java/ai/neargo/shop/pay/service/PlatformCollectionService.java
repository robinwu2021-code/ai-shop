package ai.neargo.shop.pay.service;

import java.util.*;

/** Platform-owned collection on the existing payment ledger. No consumer/submerchant fallback. */
public interface PlatformCollectionService {
    String BUSINESS = "PLATFORM_MALL";

    record Key(String tenantNo, String orderNo, String attemptNo) {}

    record Payer(String realm, String kind, String rawNo, String appId, String providerIdentity) {}

    record Request(
            Key key,
            String buyerNo,
            String merchantNo,
            String channel,
            String method,
            long amountMinor,
            String currency,
            long expiresAt,
            long preparedAt,
            Payer payer) {}

    record Receipt(
            Key key,
            String paymentNo,
            Request request,
            String state,
            String providerNo,
            Long paidAt,
            Map<String, String> checkoutParameters,
            String closureReference,
            String errorCode) {}

    Receipt create(Request request);

    Receipt query(Key key);

    Receipt close(Key key, Request original);

    Optional<Key> paymentKey(String paymentNoOrOutTradeNo);
}
