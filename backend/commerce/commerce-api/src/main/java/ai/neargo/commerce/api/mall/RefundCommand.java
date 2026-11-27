package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

import java.util.List;

/** cashAmount asserts the preview ceiling; only independent approval can authorize payment. */
public record RefundCommand(
        StoreRef store,
        String orderNo,
        String refundNo,
        Money cashAmount,
        String reason,
        List<String> sourcePeriodNos,
        HardwareRefund hardware) {
    public RefundCommand(
            StoreRef store,
            String orderNo,
            String refundNo,
            Money cashAmount,
            String reason,
            List<String> sourcePeriodNos) {
        this(store, orderNo, refundNo, cashAmount, reason, sourcePeriodNos, null);
    }

    public RefundCommand {
        sourcePeriodNos = sourcePeriodNos == null ? List.of() : List.copyOf(sourcePeriodNos);
    }

    public RefundCommand(
            StoreRef store, String orderNo, String refundNo, Money cashAmount, String reason) {
        this(store, orderNo, refundNo, cashAmount, reason, List.of());
    }
}
