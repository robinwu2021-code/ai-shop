package ai.neargo.shop.pay.service;

import java.util.*;

/** Platform-owned collection on the existing payment ledger. No consumer/submerchant fallback. */
public interface PlatformCollectionService {
    String BUSINESS = "PLATFORM_MALL";
    String UNMATCHED_BUSINESS = "MALL_UNMATCHED";
    static boolean ownsScene(String scene) { return BUSINESS.equals(scene) || UNMATCHED_BUSINESS.equals(scene); }


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
            Payer payer, OfflineInstructions offline) {
        public Request(Key key, String buyerNo, String merchantNo, String channel, String method,
                long amountMinor, String currency, long expiresAt, long preparedAt, Payer payer) {
            this(key, buyerNo, merchantNo, channel, method, amountMinor, currency, expiresAt, preparedAt, payer, null);
        }
    }

    record OfflineProfile(String tenantNo, String profileNo, long expectedVersion, String merchantNo,
            String currency, String bankCode, String bankAccount, String accountName,
            String paymentWindowVersion, long paymentWindowMs, boolean enabled, String operatorNo, String reason) {}
    record OfflineInstructions(String profileNo, long version, String merchantNo, String currency,
            String receivingAccountKey, String paymentWindowVersion, long paymentWindowMs, Map<String, String> instructions) {
        /** Sorted for byte-stable JSON across instances (Map.copyOf order is salted per JVM). */
        public OfflineInstructions { instructions = Collections.unmodifiableMap(new TreeMap<>(instructions)); }
    }
    /** Precision is assigned by this trusted manual rail, never supplied by the HTTP caller. */
    record OfflineProof(Key key, String operatorNo, String actualReceiver, long actualMinor, String currency,
            long actualPaidAt, String bankReference, String evidenceReference, String mode, long confirmedAt) {}
    record OfflineVoid(Key key, String operatorNo, String reconciliationReference, String reason,
            String evidenceReference, long reconciledAt, Map<String, String> unmatchedDispositions) {
        public OfflineVoid { unmatchedDispositions = Collections.unmodifiableMap(new TreeMap<>(unmatchedDispositions)); }
    }
    record ManualReceipt(String disposition, Receipt receipt) {}
    default OfflineInstructions configureOffline(OfflineProfile profile) { throw new UnsupportedOperationException(); }
    default Optional<OfflineInstructions> offlineInstructions(String tenant, String profile, String currency) { return Optional.empty(); }
    default ManualReceipt confirmOffline(OfflineProof proof) { throw new UnsupportedOperationException(); }
    default Receipt reconcileOffline(OfflineVoid decision) { throw new UnsupportedOperationException(); }


    record Receipt(
            Key key,
            String paymentNo,
            Request request,
            String state,
            String providerNo,
            Long paidAt,
            Map<String, String> checkoutParameters,
            String closureReference,
            String errorCode,
            Long paidAtPrecisionMs) {
        public Receipt {
            paidAtPrecisionMs = paidAtPrecisionMs == null ? 1L : paidAtPrecisionMs;
            if (paidAtPrecisionMs != 1 && paidAtPrecisionMs != 1000)
                throw new IllegalArgumentException("INVALID_PAYMENT_TIME_PRECISION");
            if (paidAt != null) {
                try {
                    Math.addExact(paidAt, paidAtPrecisionMs - 1);
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("INVALID_PAYMENT_TIME_RANGE", overflow);
                }
            }
        }

        public Receipt(
                Key key,
                String paymentNo,
                Request request,
                String state,
                String providerNo,
                Long paidAt,
                Map<String, String> checkoutParameters,
                String closureReference,
                String errorCode) {
            this(
                    key,
                    paymentNo,
                    request,
                    state,
                    providerNo,
                    paidAt,
                    checkoutParameters,
                    closureReference,
                    errorCode,
                    1L);
        }
    }

    Receipt create(Request request);

    Receipt query(Key key);

    Receipt close(Key key, Request original);

    Optional<Key> paymentKey(String paymentNoOrOutTradeNo);
}
