package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

import java.util.List;

public record RefundPreviewCommand(
        StoreRef store,
        String orderNo,
        String refundNo,
        List<String> sourcePeriodNos,
        HardwareRefund hardware) {
    public RefundPreviewCommand(
            StoreRef store, String orderNo, String refundNo, List<String> sourcePeriodNos) {
        this(store, orderNo, refundNo, sourcePeriodNos, null);
    }

    public RefundPreviewCommand {
        sourcePeriodNos = sourcePeriodNos == null ? List.of() : List.copyOf(sourcePeriodNos);
    }
}
