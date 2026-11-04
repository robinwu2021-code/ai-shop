package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.catalog.SkuDraft;
import ai.neargo.commerce.api.common.*;

import java.util.List;

/**
 * Pure trusted local deployment metadata only: never network, stock IO or locks in this callback.
 */
@FunctionalInterface
public interface CheckoutReadinessPort {
    Readiness evaluate(StoreSnapshot store, Money payable, List<SkuDraft> frozenSkus);

    record PaymentOption(String channel, String payMethod, String currency, long maximumMinor) {}

    record Readiness(boolean available, List<String> reasons, List<PaymentOption> paymentOptions) {
        public Readiness {
            reasons = List.copyOf(reasons);
            paymentOptions = List.copyOf(paymentOptions);
        }
    }
}
