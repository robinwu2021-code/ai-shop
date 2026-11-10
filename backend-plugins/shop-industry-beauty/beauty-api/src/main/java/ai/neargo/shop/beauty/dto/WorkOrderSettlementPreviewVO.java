package ai.neargo.shop.beauty.dto;

import java.util.List;

/** No preview number, payment instruction or executable permission is issued by this read. */
public record WorkOrderSettlementPreviewVO(
        WorkOrderSettlementVO settlement,
        String currency,
        long walletMinor,
        long externalMinor,
        String method,
        boolean executable,
        List<String> blockingReasons) {
    public WorkOrderSettlementPreviewVO { blockingReasons = List.copyOf(blockingReasons); }
}
