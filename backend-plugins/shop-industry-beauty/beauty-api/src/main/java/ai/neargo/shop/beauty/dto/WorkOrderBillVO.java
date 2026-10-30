package ai.neargo.shop.beauty.dto;

import java.time.Instant;
import java.util.List;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record WorkOrderBillVO(
        String workOrderNo,
        long scopeRevision,
        String localWatermark,
        Instant observedAt,
        List<SourceBillVO> sources,
        List<CurrencyBillVO> currencies,
        boolean complete,
        List<String> blockingReasons) {
    public WorkOrderBillVO {
        sources = List.copyOf(sources);
        currencies = List.copyOf(currencies);
        blockingReasons = List.copyOf(blockingReasons);
    }
}
