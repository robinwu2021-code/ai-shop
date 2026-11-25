package ai.neargo.shop.trade.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.TriggerType;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.order.refund.service.PointsRefundResourceService;
import ai.neargo.shop.trade.service.AfterSaleService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(OutputCaptureExtension.class)
class RefundRetryJobTest {

    private final AfterSaleService sales = mock(AfterSaleService.class);
    private final PointsRefundResourceService resources = mock(PointsRefundResourceService.class);
    private final RefundRetryJob job = new RefundRetryJob(sales, mock(JobSupport.class));

    RefundRetryJobTest() {
        job.setPointsResources(resources);
        ReflectionTestUtils.setField(job, "limit", 10);
    }

    @Test
    void pendingRefundsAndStockAreCountedAndWarnedOnceUntilTheyRecover(CapturedOutput logs) {
        when(resources.pendingNos(10)).thenReturn(List.of("STOCK-1", "STOCK-2", "STOCK-3"));
        when(resources.recover("STOCK-1")).thenThrow(new IllegalStateException("sensitive-stock-marker"))
                .thenThrow(new IllegalStateException("sensitive-stock-marker")).thenReturn(true);
        when(resources.recover("STOCK-2")).thenReturn(true);
        when(resources.recover("STOCK-3")).thenReturn(false, false, true);
        when(sales.refundedWithoutSplitReversal(anyLong(), eq(10))).thenReturn(List.of());
        var refunds = List.of("CASH-1", "CASH-2");
        when(sales.stuckRefundNos(anyLong(), eq(10))).thenReturn(refunds);
        when(sales.refundedNosForRecovery(refunds)).thenReturn(Set.of("CASH-2"), Set.of("CASH-2"), Set.copyOf(refunds));

        job.run(invocation("RUN-1"));
        job.run(invocation("RUN-2"));
        job.run(invocation("RUN-3"));

        verify(resources, times(3)).recover("STOCK-2");
        verify(sales, times(3)).resumeRefund("CASH-1", "SYSTEM");
        verify(sales, times(3)).resumeRefund("CASH-2", "SYSTEM");
        assertThat(logs.getAll()).contains(
                "runId=RUN-1 afterSaleNo=STOCK-1 reasonCode=STOCK_RECOVERY_FAILED",
                "runId=RUN-1 afterSaleNo=STOCK-3 reasonCode=STOCK_PENDING",
                "runId=RUN-1 afterSaleNo=CASH-1 reasonCode=REFUND_PENDING",
                "runId=RUN-1 refundScanned=2 refundAdvanced=1 refundPending=1 stockScanned=3 stockAdvanced=1 stockPending=2 i4Count=0 alerts=3",
                "runId=RUN-2 refundScanned=2 refundAdvanced=1 refundPending=1 stockScanned=3 stockAdvanced=1 stockPending=2 i4Count=0 alerts=0",
                "runId=RUN-3 refundScanned=2 refundAdvanced=2 refundPending=0 stockScanned=3 stockAdvanced=3 stockPending=0 i4Count=0 alerts=0",
                "refund.recovery.stock runId=RUN-3 afterSaleNo=STOCK-1 result=RECOVERED",
                "refund.recovery.cash runId=RUN-3 afterSaleNo=CASH-1 result=RECOVERED")
                .doesNotContain("sensitive-stock-marker", "runId=RUN-2 afterSaleNo=");
    }

    @Test
    void knownBusinessFailureUsesErrorCodeAndI4WarnsOnlyOnNewCases(CapturedOutput logs) {
        var first = List.of("I4-1", "I4-2", "I4-3", "I4-4", "I4-5", "I4-6");
        var later = List.of("I4-1", "I4-2", "I4-3", "I4-4", "I4-5", "I4-6", "I4-7");
        when(sales.refundedWithoutSplitReversal(anyLong(), eq(10))).thenReturn(first, first, later);
        when(sales.stuckRefundNos(anyLong(), eq(10))).thenReturn(List.of("CASH-1"));
        doThrow(BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT, "sensitive-cash-marker"))
                .when(sales).resumeRefund("CASH-1", "SYSTEM");

        job.run(invocation("RUN-1"));
        job.run(invocation("RUN-2"));
        job.run(invocation("RUN-3"));

        assertThat(logs.getAll()).contains(
                "refund.recovery.i4 runId=RUN-1 reasonCode=SPLIT_NOT_REVERSED count=6 newCases=6 samples=[I4-1, I4-2, I4-3, I4-4, I4-5]",
                "refund.recovery.i4 runId=RUN-3 reasonCode=SPLIT_NOT_REVERSED count=7 newCases=1 samples=[I4-7]",
                "refund.recovery.cash runId=RUN-1 afterSaleNo=CASH-1 reasonCode=CHECKOUT_PAYMENT_CONFLICT",
                "i4Count=6 alerts=7", "i4Count=6 alerts=0", "i4Count=7 alerts=1")
                .doesNotContain("refund.recovery.i4 runId=RUN-2", "sensitive-cash-marker");
    }

    @Test
    void unavailableStatusReadKeepsCompletedAttemptsOutOfSchedulerRetry(CapturedOutput logs) {
        var refunds = List.of("CASH-1");
        when(sales.stuckRefundNos(anyLong(), eq(10))).thenReturn(refunds);
        when(sales.refundedNosForRecovery(refunds))
                .thenThrow(new DataAccessResourceFailureException("sensitive-database-marker"));

        assertThat(job.run(invocation("RUN-1")).detail()).contains("推进 0 · 待处理 1");
        verify(sales).resumeRefund("CASH-1", "SYSTEM");
        assertThat(logs.getAll()).contains("runId=RUN-1 afterSaleNo=CASH-1 reasonCode=REFUND_STATUS_UNCONFIRMED")
                .doesNotContain("sensitive-database-marker");
    }

    private static JobInvocation invocation(String runId) {
        return new JobInvocation(runId, TriggerType.CRON, null, Map.of());
    }
}
