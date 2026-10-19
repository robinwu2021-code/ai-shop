package ai.neargo.shop.trade.job;

import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.order.refund.service.PointsRefundResourceService;
import ai.neargo.shop.trade.service.AfterSaleService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class RefundRetryJobTest {
    @Test void inventory_has_its_own_batch_and_one_failure_does_not_block_the_next_task() {
        var sales = mock(AfterSaleService.class);
        var resources = mock(PointsRefundResourceService.class);
        var job = new RefundRetryJob(sales, mock(JobSupport.class));
        job.setPointsResources(resources);
        ReflectionTestUtils.setField(job, "limit", 2);
        when(resources.pendingNos(2)).thenReturn(List.of("STOCK-1", "STOCK-2"));
        doThrow(new IllegalStateException("TEMPORARY")).when(resources).recover("STOCK-1");
        when(sales.refundedWithoutSplitReversal(anyLong(), eq(2))).thenReturn(List.of());
        when(sales.stuckRefundNos(anyLong(), eq(2))).thenReturn(List.of("CASH-1", "CASH-2"));

        job.run(null);

        verify(resources).recover("STOCK-1");
        verify(resources).recover("STOCK-2");
        verify(sales).resumeRefund("CASH-1", "SYSTEM");
        verify(sales).resumeRefund("CASH-2", "SYSTEM");
    }
}
