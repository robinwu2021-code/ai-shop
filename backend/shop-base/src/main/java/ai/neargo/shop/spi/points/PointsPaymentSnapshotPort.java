package ai.neargo.shop.spi.points;

import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only proof of a successful cash payment and its immutable payment-open request. */
public interface PointsPaymentSnapshotPort {
    Optional<VerifiedPayment> verified(String orderNo);

    record VerifiedPayment(
            String orderNo,
            String userNo,
            String currency,
            long amountMinor,
            String channel,
            String outTradeNo,
            String tradeNo,
            Instant succeededAt,
            List<PaymentStore> stores) {
        public VerifiedPayment {
            stores = List.copyOf(stores);
        }

        public PointsRefundPort.PaymentSnapshot forSubOrder(String subOrderNo) {
            var store =
                    stores.stream()
                            .filter(s -> s.subOrderNo().equals(subOrderNo))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "POINTS_PAYMENT_STORE_MISSING"));
            var route = store.route();
            return new PointsRefundPort.PaymentSnapshot(
                    route.fundsPath(),
                    route.channel(),
                    route.merchantNo(),
                    route.storeNo(),
                    route.accountVersion(),
                    route.subMerchantId(),
                    tradeNo,
                    outTradeNo,
                    amountMinor);
        }
    }
}
