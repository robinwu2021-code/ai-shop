package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.spi.trade.CheckoutPort;

import java.util.List;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record ServiceSubmissionVO(
        String submissionNo,
        String workOrderNo,
        String action,
        long revision,
        long originalScopeRevision,
        long targetScopeRevision,
        String status,
        String phase,
        String checkoutOperationNo,
        CheckoutPort.CheckoutQuote quote,
        String orderNo,
        String subOrderNo,
        SourceObservationVO source,
        List<SubmissionTaskVO> tasks,
        String recoveryAction,
        List<String> blockingReasons) {
    public ServiceSubmissionVO {
        tasks = List.copyOf(tasks);
        blockingReasons = List.copyOf(blockingReasons);
    }
}
