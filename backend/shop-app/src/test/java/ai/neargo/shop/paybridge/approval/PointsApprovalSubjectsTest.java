package ai.neargo.shop.paybridge.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.points.admin.PointsAdjustmentService;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService;
import ai.neargo.shop.pay.points.admin.PointsAdjustmentService.Direction;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.payclient.PointsTreasuryAppService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** T8 · 积分资金池申请与用户调账接入审批（方案-第一批审批工作流 T8）。 */
@DisplayName("积分审批：生效 / 驳回走原复核入口，操作人是审批服务；版本不符 STALE；提交后与补送都送审")
class PointsApprovalSubjectsTest {

    final PointsAdjustmentService adjustments = mock(PointsAdjustmentService.class);
    final PointsTreasuryService treasury = mock(PointsTreasuryService.class);
    final PointsTreasuryAppService app = mock(PointsTreasuryAppService.class);

    static PointsAdjustmentService.View adj(String state, long version) {
        return new PointsAdjustmentService.View(1, "A1", "T", "U1", Direction.INCREASE, 10, "更正", state, "MAKER", null, version, 1L, null);
    }

    static PointsTreasuryService.RequestView req(String state, long version) {
        return new PointsTreasuryService.RequestView(1, "R1", "T", PoolRequest.Action.TOPUP_U, 123450, state, "MAKER", null, 1, 0,
                "备款", null, true, null, version);
    }

    @Test
    void adjustmentApplyReviewsAsApprovalServiceWithFrozenRevision() {
        when(adjustments.adjustment("A1")).thenReturn(Optional.of(adj("SUBMITTED", 3)));
        var subject = new PointsAdjustmentApprovalSubject(adjustments);
        assertThat(subject.level()).isEqualTo(ApprovalSubject.Level.FUNDS);
        subject.apply("A1", 3);
        var review = ArgumentCaptor.forClass(PointsAdjustmentService.Review.class);
        verify(adjustments).review(review.capture(), eq("OPSKIT"));
        assertThat(review.getValue().adjustmentNo()).isEqualTo("A1");
        assertThat(review.getValue().typeCode()).isEqualTo("T");
        assertThat(review.getValue().expectedVersion()).isEqualTo(3);
        assertThat(review.getValue().approve()).isTrue();
    }

    @Test
    void adjustmentStaleAndReplay() {
        var subject = new PointsAdjustmentApprovalSubject(adjustments);
        when(adjustments.adjustment("A1")).thenReturn(Optional.of(adj("SUBMITTED", 4)));
        assertThatThrownBy(() -> subject.apply("A1", 3))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo(ApprovalApplyException.STALE));
        when(adjustments.adjustment("A1")).thenReturn(Optional.of(adj("APPROVED", 5)));
        subject.apply("A1", 4); // 重投：已生效
        verify(adjustments, never()).review(any(), anyString());
    }

    @Test
    void adjustmentBusinessRejectionBecomesApplyFailure() {
        when(adjustments.adjustment("A1")).thenReturn(Optional.of(adj("SUBMITTED", 3)));
        when(adjustments.review(any(), anyString())).thenThrow(new IllegalArgumentException("POINTS_TYPE_NOT_ACTIVE"));
        assertThatThrownBy(() -> new PointsAdjustmentApprovalSubject(adjustments).apply("A1", 3))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isNotEqualTo(ApprovalApplyException.STALE));
    }

    @Test
    void adjustmentRejectedWritesBackRejection() {
        when(adjustments.adjustment("A1")).thenReturn(Optional.of(adj("SUBMITTED", 3)));
        new PointsAdjustmentApprovalSubject(adjustments).onClosed("A1", 3, ApprovalSubject.Outcome.REJECTED, "金额不对");
        var review = ArgumentCaptor.forClass(PointsAdjustmentService.Review.class);
        verify(adjustments).review(review.capture(), eq("OPSKIT"));
        assertThat(review.getValue().approve()).isFalse();
        assertThat(review.getValue().reason()).isEqualTo("金额不对");
    }

    @Test
    void poolRequestApplyAndFacts() {
        when(treasury.request("R1")).thenReturn(Optional.of(req("SUBMITTED", 2)));
        var subject = new PointsPoolRequestApprovalSubject(treasury, app);
        assertThat(subject.snapshot("R1").facts()).anySatisfy(f -> assertThat(f.value()).isEqualTo("1234.50"));
        subject.apply("R1", 2);
        var review = ArgumentCaptor.forClass(PointsTreasuryService.Review.class);
        verify(app).review(review.capture(), eq("OPSKIT"));
        assertThat(review.getValue().requestNo()).isEqualTo("R1");
        assertThat(review.getValue().expectedVersion()).isEqualTo(2);
        assertThat(review.getValue().approve()).isTrue();
    }

    @Test
    void submitterSendsCurrentVersionAndResendsUnsentOnes() {
        var support = mock(PayApprovalSupport.class);
        when(support.enabled(anyString())).thenReturn(true);
        when(treasury.request("R1")).thenReturn(Optional.of(req("SUBMITTED", 0)));
        when(treasury.submitted(anyInt())).thenReturn(List.of(req("SUBMITTED", 0)));
        when(adjustments.submitted(anyInt())).thenReturn(List.of(adj("SUBMITTED", 0)));
        var submitter = new PointsApprovalSubmitter(support, treasury, adjustments);
        submitter.poolRequestSubmitted("R1", "OPS1");
        verify(support).submit(PointsPoolRequestApprovalSubject.SUBJECT_TYPE, "R1", 0, "OPS1", null, null);
        submitter.resend();
        verify(support).submit(PointsPoolRequestApprovalSubject.SUBJECT_TYPE, "R1", 0, "MAKER", null, null);
        verify(support).submit(PointsAdjustmentApprovalSubject.SUBJECT_TYPE, "A1", 0, "MAKER", null, null);
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @SuppressWarnings("unused")
    private static long anyLongValue() {
        return anyLong();
    }
}
