package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;

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
        List<String> blockingReasons,
        CounterCheckoutQuote counterQuote) {
    /** Retains the previous public constructor and funded projection defaults. */
    public ServiceSubmissionVO(
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
        this(
                submissionNo,
                workOrderNo,
                action,
                revision,
                originalScopeRevision,
                targetScopeRevision,
                status,
                phase,
                checkoutOperationNo,
                quote,
                orderNo,
                subOrderNo,
                source,
                tasks,
                recoveryAction,
                blockingReasons,
                null);
    }

    public ServiceSubmissionVO {
        tasks = List.copyOf(tasks);
        blockingReasons = List.copyOf(blockingReasons);
    }
}
