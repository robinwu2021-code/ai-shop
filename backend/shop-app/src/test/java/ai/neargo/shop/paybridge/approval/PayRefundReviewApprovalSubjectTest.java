package ai.neargo.shop.paybridge.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.payclient.OpsPaymentAppService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 退款复核审批主题（TDD-基础能力库对齐 §2.2 1e，Q12）。 */
class PayRefundReviewApprovalSubjectTest {

    private final OpsPaymentAppService app = mock(OpsPaymentAppService.class);
    private final MerchantQueryPort merchants = mock(MerchantQueryPort.class);
    private final PayRefundReviewApprovalSubject subject = new PayRefundReviewApprovalSubject(app, merchants);

    private static RefundRow row(String no) {
        return new RefundRow(no, null, 1990, "CNY", "PENDING_REVIEW", "七天无理由", "OP-1", "MALL_ORDER",
                "NO_ACTIVE_RULE", 1_700_000_000_000L);
    }

    @Test
    void fundsLevelWithSeparateApproversOnTheRefundReviewPermission() {
        assertThat(subject.level()).isEqualTo(ApprovalSubject.Level.FUNDS);
        assertThat(subject.separateApprovers()).isTrue();
        assertThat(subject.approverPerm()).isEqualTo(Perms.PAY_REFUND_REVIEW);
        assertThat(subject.recheckPerm()).isEqualTo(Perms.PAY_REFUND_REVIEW);
    }

    @Test
    void snapshotShowsAmountAndRequesterAndRevisionIsCreationTime() {
        when(app.refunds(eq("PENDING_REVIEW"), anyInt())).thenReturn(List.of(row("PR-1")));
        var snapshot = subject.snapshot("PR-1");
        assertThat(snapshot.title()).contains("PR-1").contains("19.90");
        assertThat(snapshot.facts()).extracting(ApprovalSubject.Fact::value).contains("OP-1");
        assertThat(PayRefundReviewApprovalSubject.revisionOf(row("PR-1"))).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void applyReleasesAndAnyClosingRejects() {
        subject.apply("PR-1", 1L);
        verify(app).applyRefundApproval("PR-1", true, "审批通过");
        subject.onClosed("PR-2", 1L, ApprovalSubject.Outcome.WITHDRAWN, null);
        verify(app).applyRefundApproval(eq("PR-2"), eq(false), eq("审批未通过（WITHDRAWN）"));
    }

    @Test
    void unknownRefundIsNotFound() {
        assertThatThrownBy(() -> subject.snapshot("PR-X")).isInstanceOf(ApprovalApplyException.class);
    }
}
