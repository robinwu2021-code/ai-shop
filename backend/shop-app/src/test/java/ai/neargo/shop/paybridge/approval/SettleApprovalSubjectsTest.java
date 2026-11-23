package ai.neargo.shop.paybridge.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.PayoutService;
import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.SettleBatchService.BatchRevision;
import ai.neargo.shop.pay.SettleBatchService.BatchVO;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T7 · 结算批次放行与放款接入审批（方案-第一批审批工作流 T7）。 */
@DisplayName("结算审批：放行 / 放款在执行节点才生效；修订不符 STALE；放款闸门不过 → 生效失败；系统挂起的批次自动送审")
class SettleApprovalSubjectsTest {

    final SettleBatchService batches = mock(SettleBatchService.class);
    final PayoutService payouts = mock(PayoutService.class);

    static BatchVO batch(String status, String decidedBy) {
        return new BatchVO("B1", "E1", "WX", "T1", 0, 0, null, null, status, 3, 10000, 9800, null, "R6", null, null, decidedBy, null, null, null, null);
    }

    void current(String status, long revision) {
        when(batches.revision("B1")).thenReturn(Optional.of(new BatchRevision(batch(status, null), revision)));
    }

    @Test
    void releaseAppliesApproveAndHoldsOnRejection() {
        current("BLOCKED", 100);
        var subject = new SettleReleaseApprovalSubject(batches);
        assertThat(subject.snapshot("B1").scopes()).containsExactly("merchant:E1");
        subject.apply("B1", 100);
        verify(batches).approve("B1", "OPSKIT", "审批通过");
        subject.onClosed("B1", 100, ApprovalSubject.Outcome.REJECTED, "差异未查清");
        verify(batches).hold("B1", "OPSKIT", "差异未查清");
    }

    @Test
    void releaseStaleAndReplay() {
        current("BLOCKED", 101);
        var subject = new SettleReleaseApprovalSubject(batches);
        assertThatThrownBy(() -> subject.apply("B1", 100))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo(ApprovalApplyException.STALE));
        current("RECONCILED", 102);
        subject.apply("B1", 100);
        verify(batches, never()).approve(anyString(), anyString(), anyString());
    }

    @Test
    void payoutReleasesAndGateFailureBecomesApplyFailure() {
        current("RECONCILED", 200);
        var audit = mock(AuditLogPort.class);
        var subject = new SettlePayoutApprovalSubject(batches, payouts, audit);
        assertThat(subject.level()).isEqualTo(ApprovalSubject.Level.FUNDS);
        when(payouts.releaseBatch("B1", "OPSKIT")).thenReturn(List.of());
        subject.apply("B1", 200);
        verify(payouts).releaseBatch("B1", "OPSKIT");
        verify(audit).record(eq("SETTLE_BATCH_RELEASE"), eq("B1"), anyString(), eq(true));

        when(payouts.releaseBatch("B1", "OPSKIT")).thenThrow(BizException.of(ErrorCode.PAYOUT_INVOICE_PENDING));
        assertThatThrownBy(() -> subject.apply("B1", 200))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo("PAYOUT_INVOICE_PENDING"));
    }

    @Test
    void systemBlockedBatchesAreSubmittedButHeldOnesAreNot() {
        var support = mock(PayApprovalSupport.class);
        when(support.enabled(anyString())).thenReturn(true);
        when(batches.opsBatches("BLOCKED", null)).thenReturn(List.of(batch("BLOCKED", null),
                new BatchVO("B2", "E1", "WX", "T1", 0, 0, null, null, "BLOCKED", 1, 1, 1, null, "人工", null, null, "OPS1", "挂起", null, null, null)));
        current("BLOCKED", 300);
        new SettleApprovalSubmitter(support, batches).submitBlocked();
        verify(support).submit(SettleReleaseApprovalSubject.SUBJECT_TYPE, "B1", 300, "SYSTEM", "对账任务", null);
        verify(support, never()).submit(eq(SettleReleaseApprovalSubject.SUBJECT_TYPE), eq("B2"), any(Long.class), anyString(), anyString(), any());
    }
}
