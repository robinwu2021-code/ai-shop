package ai.neargo.shop.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.api.ops.OpsStoreController;
import ai.neargo.shop.merchant.approval.StoreOpeningApprovalSubject;
import ai.neargo.shop.merchant.service.StoreOperationalReviewService;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.Review;
import ai.neargo.shop.paybridge.approval.PointsApprovalSubmitter;
import ai.neargo.shop.payclient.PointsTreasuryAppService;
import ai.neargo.shop.portal.ops.pay.OpsPointsTreasuryController;
import ai.neargo.shop.spi.approval.ApprovalPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 第一批收尾：接入审批后，运营端原来的「直接决定」入口不能再绕过审批中心（T1 门店开通、T8 积分资金池）。 */
@DisplayName("旧的直接决定入口：启用审批时拒绝（APPROVAL_IN_OPSKIT），未启用时照旧")
class LegacyDecisionBlockTest {

    @Test
    @SuppressWarnings("unchecked")
    void storeOpeningDecideIsRefusedWhileApprovalIsOn() {
        var reviews = mock(StoreOperationalReviewService.class);
        var controller = new OpsStoreController(null, null, null, reviews);
        ObjectProvider<ApprovalPort> provider = mock(ObjectProvider.class);
        var port = mock(ApprovalPort.class);
        when(provider.getIfAvailable()).thenReturn(port);
        controller.setApprovals(provider);
        var req = new OpsStoreController.OperationalDecisionReq(true, "资料齐全");

        when(port.enabled(StoreOpeningApprovalSubject.SUBJECT_TYPE)).thenReturn(true);
        assertThatThrownBy(() -> controller.decideOperationalReview("S1", req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.APPROVAL_IN_OPSKIT));
        verify(reviews, never()).decide(anyString(), anyBoolean(), anyString());

        when(port.enabled(StoreOpeningApprovalSubject.SUBJECT_TYPE)).thenReturn(false);
        controller.decideOperationalReview("S1", req);
        verify(reviews).decide("S1", true, "资料齐全");
    }

    @Test
    void pointsPoolReviewIsRefusedWhileApprovalIsOn() {
        var service = mock(PointsTreasuryAppService.class);
        var approvals = mock(PointsApprovalSubmitter.class);
        var controller = new OpsPointsTreasuryController(service, approvals);
        when(approvals.poolEnabled()).thenReturn(true);
        assertThatThrownBy(() -> controller.review(mock(Review.class)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.APPROVAL_IN_OPSKIT));
        verify(service, never()).review(any(), any());
    }
}
