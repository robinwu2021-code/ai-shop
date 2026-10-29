package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.beauty.dto.ReceiptSummaryVO;
import ai.neargo.shop.spi.trade.CheckoutPort;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure validation of one fresh owner response; no money is persisted or inferred from local
 * quantities.
 */
public final class ServiceSourceFacts {
    private ServiceSourceFacts() {}

    public record Money(
            String currency,
            Long due,
            Long received,
            Long refunded,
            Long netReceived,
            Long outstanding,
            List<ReceiptSummaryVO> receipts,
            String reason) {
        public Money {
            receipts = List.copyOf(receipts);
        }

        public boolean complete() {
            return reason == null;
        }
    }

    /** Unknown owner due=0 is an unavailable amount. Exact receipt aliases contribute once. */
    public static Money money(CheckoutPort.CheckoutSourceFacts facts) {
        String currency =
                facts != null && facts.currency() != null && facts.currency().matches("[A-Z]{3}")
                        ? facts.currency()
                        : null;
        if (facts == null || "UNKNOWN".equals(facts.financialState())) {
            return unavailable(currency, "SOURCE_UNKNOWN");
        }
        if (currency == null
                || facts.dueMinor() < 0
                || facts.receivedMinor() == null
                || facts.receivedMinor() < 0
                || facts.outstandingMinor() == null
                || facts.outstandingMinor() < 0
                || !Set.of("PAID", "PARTIALLY_REFUNDED", "REFUNDED")
                        .contains(facts.financialState())) {
            return unavailable(currency, "SOURCE_MONEY_INVALID");
        }
        var unique = new TreeMap<String, CheckoutPort.PaymentFact>();
        try {
            long lineDue = 0;
            for (var line : facts.lines()) {
                if (line == null || line.dueMinor() < 0) {
                    return unavailable(currency, "SOURCE_MONEY_INVALID");
                }
                lineDue = Math.addExact(lineDue, line.dueMinor());
            }
            if (facts.lines().isEmpty() || lineDue != facts.dueMinor()) {
                return unavailable(currency, "SOURCE_MONEY_INVALID");
            }
            for (var payment : facts.payments()) {
                if (payment == null
                        || payment.amountMinor() < 0
                        || !currency.equals(payment.currency())
                        || !reference(payment.paymentNo())
                        || !reference(payment.channel())
                        || !reference(payment.status())
                        || payment.observedAt() == null
                        || payment.receiptNo() != null && !reference(payment.receiptNo())
                        || payment.paymentOperationNo() != null
                                && !reference(payment.paymentOperationNo())
                        || payment.outTradeNo() != null && !reference(payment.outTradeNo())) {
                    return unavailable(currency, "SOURCE_RECEIPT_INVALID");
                }
                String key = receiptIdentity(facts.orderNo(), payment);
                if (key == null) {
                    return unavailable(currency, "SOURCE_RECEIPT_INVALID");
                }
                var before = unique.putIfAbsent(key, payment);
                if (before != null
                        && (before.amountMinor() != payment.amountMinor()
                                || !before.currency().equals(payment.currency())
                                || !before.status().equals(payment.status())
                                || !before.channel().equals(payment.channel())
                                || !Objects.equals(before.tradeNo(), payment.tradeNo()))) {
                    return unavailable(currency, "SOURCE_RECEIPT_CONFLICT");
                }
            }
            long gross = 0;
            for (var receipt : unique.values()) {
                if ("SUCCESS".equals(receipt.status())) {
                    gross = Math.addExact(gross, receipt.amountMinor());
                }
            }
            long refunded = Math.subtractExact(gross, facts.receivedMinor());
            if (refunded < 0 || refunded > gross) {
                return unavailable(currency, "SOURCE_MONEY_INVALID");
            }
            return new Money(
                    currency,
                    facts.dueMinor(),
                    gross,
                    refunded,
                    facts.receivedMinor(),
                    facts.outstandingMinor(),
                    unique.values().stream()
                            .map(
                                    receipt ->
                                            new ReceiptSummaryVO(
                                                    receipt.paymentOperationNo(),
                                                    receipt.paymentNo(),
                                                    receipt.outTradeNo(),
                                                    receipt.receiptNo(),
                                                    receipt.channel(),
                                                    receipt.status(),
                                                    receipt.amountMinor(),
                                                    receipt.currency()))
                            .toList(),
                    null);
        } catch (ArithmeticException overflow) {
            return unavailable(currency, "SOURCE_MONEY_OVERFLOW");
        }
    }

    public static String receiptIdentity(String orderNo, CheckoutPort.PaymentFact payment) {
        if (payment.receiptNo() != null) {
            return "RECEIPT:" + payment.receiptNo();
        }
        return reference(orderNo) && reference(payment.tradeNo())
                ? "LEGACY:"
                        + payment.paymentNo()
                        + ":"
                        + orderNo
                        + ":"
                        + payment.channel()
                        + ":"
                        + payment.tradeNo()
                : null;
    }

    public static Money unavailable(String currency, String reason) {
        return new Money(currency, null, null, null, null, null, List.of(), reason);
    }

    private static boolean reference(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 128
                && value.equals(value.strip());
    }
}
