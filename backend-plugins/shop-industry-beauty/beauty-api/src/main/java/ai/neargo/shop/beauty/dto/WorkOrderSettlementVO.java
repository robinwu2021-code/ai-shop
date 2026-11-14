package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Read completeness and permission to collect are separate facts. */
public record WorkOrderSettlementVO(
        WorkOrderBillVO bill,
        String fingerprint,
        List<SourceDecision> sources,
        boolean complete,
        boolean canSettle,
        List<String> methods,
        List<String> blockingReasons) {
    public WorkOrderSettlementVO {
        sources = List.copyOf(sources);
        methods = List.copyOf(methods);
        blockingReasons = List.copyOf(blockingReasons);
    }

    public record SourceDecision(
            String checkoutOperationNo,
            String orderNo,
            String sourceNo,
            String state,
            boolean canCollect,
            List<String> blockingReasons) {
        public SourceDecision { blockingReasons = List.copyOf(blockingReasons); }
    }
}
