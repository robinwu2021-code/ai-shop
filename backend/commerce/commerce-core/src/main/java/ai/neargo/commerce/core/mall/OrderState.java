package ai.neargo.commerce.core.mall;

import ai.neargo.commerce.api.mall.*;

/** Persisted lifecycle axes. A projection never invents successful payment or fulfillment. */
public final class OrderState {
    private OrderState() {}

    public enum Payment {
        UNPAID,
        PAYING,
        PAYMENT_UNKNOWN,
        CANCEL_PENDING,
        PAID,
        CLOSED
    }

    public enum Fulfillment {
        WAIT_INVENTORY,
        PENDING,
        PROCESSING,
        PARTIAL,
        COMPLETED,
        FAILED
    }

    public enum Refund {
        NONE,
        REFUNDING,
        PARTIAL,
        REFUNDED
    }

    public record Axes(Payment payment, Fulfillment fulfillment, Refund refund) {
        public Axes {
            if (payment == null || fulfillment == null || refund == null) throw invalid();
            if (payment != Payment.PAID
                    && (refund != Refund.NONE
                            || (fulfillment != Fulfillment.WAIT_INVENTORY
                                    && fulfillment != Fulfillment.PENDING))) throw invalid();
            if ((payment == Payment.PAID
                            || payment == Payment.PAYING
                            || payment == Payment.PAYMENT_UNKNOWN)
                    && fulfillment == Fulfillment.WAIT_INVENTORY) throw invalid();
        }

        public OrderStatus status() {
            if (refund == Refund.REFUNDING) return OrderStatus.REFUNDING;
            if (refund == Refund.PARTIAL) return OrderStatus.PARTIALLY_REFUNDED;
            if (refund == Refund.REFUNDED) return OrderStatus.REFUNDED;
            return switch (payment) {
                case CANCEL_PENDING -> OrderStatus.CANCEL_PENDING;
                case CLOSED -> OrderStatus.CLOSED;
                case PAYMENT_UNKNOWN -> OrderStatus.PAYMENT_UNKNOWN;
                case UNPAID, PAYING ->
                        fulfillment == Fulfillment.WAIT_INVENTORY
                                ? OrderStatus.PENDING_INVENTORY
                                : OrderStatus.AWAITING_PAYMENT;
                case PAID ->
                        switch (fulfillment) {
                            case PENDING, FAILED -> OrderStatus.PAID;
                            case PROCESSING, PARTIAL -> OrderStatus.FULFILLING;
                            case COMPLETED -> OrderStatus.COMPLETED;
                            case WAIT_INVENTORY -> throw invalid();
                        };
            };
        }
    }

    private static MallException invalid() {
        return new MallException("INVALID_ORDER_STATE");
    }

    public static Axes parse(String payment, String fulfillment, String refund) {
        try {
            return new Axes(
                    Payment.valueOf(payment),
                    Fulfillment.valueOf(fulfillment),
                    Refund.valueOf(refund));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid();
        }
    }

    public static Axes initial(boolean hardware) {
        return new Axes(
                Payment.UNPAID,
                hardware ? Fulfillment.WAIT_INVENTORY : Fulfillment.PENDING,
                Refund.NONE);
    }

    public static Axes requestCancellation(Axes before) {
        return switch (before.payment()) {
            case UNPAID, PAYING, PAYMENT_UNKNOWN, CANCEL_PENDING ->
                    new Axes(Payment.CANCEL_PENDING, before.fulfillment(), before.refund());
            default -> throw new MallException("INVALID_STATE");
        };
    }
}
