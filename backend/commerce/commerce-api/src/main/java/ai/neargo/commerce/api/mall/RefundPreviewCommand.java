package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

import java.util.List;

public record RefundPreviewCommand(
        StoreRef store, String orderNo, String refundNo, List<String> sourcePeriodNos) {
    public RefundPreviewCommand {
        sourcePeriodNos = List.copyOf(sourcePeriodNos);
    }
}
