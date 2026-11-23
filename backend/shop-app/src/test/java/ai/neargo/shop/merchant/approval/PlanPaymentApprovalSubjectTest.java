package ai.neargo.shop.merchant.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.commerce.api.common.Money;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** T9c · 套餐线下付款确认接入审批（方案-第一批审批工作流 T9c）。 */
@DisplayName("套餐付款审批：确认只送审；通过后按冻结内容以填单人身份确认；订单变了 STALE；已确认重投成功")
class PlanPaymentApprovalSubjectTest {

    final EntityPlanPurchaseService plans = mock(EntityPlanPurchaseService.class);
    final ApprovalPort port = mock(ApprovalPort.class);
    @SuppressWarnings("unchecked") final ObjectProvider<ApprovalPort> provider = mock(ObjectProvider.class);
    final PlanPaymentApprovalSubject subject = new PlanPaymentApprovalSubject(plans, provider);

    final Confirm confirm = new Confirm("ACT-1", "CLM-1", new Money("TWD", 99900), Instant.parse("2026-11-20T02:00:00Z"), "BANK-778", "ev/receipt.png", "已核对网银");

    OrderView order(String status, List<ActionView> actions) {
        var offer = new Offer("OFF-1", 3, "PRO", "专业版年付", 365, new Money("TWD", 99900), 3, 20, true, true);
        var claim = new ClaimView("CLM-1", "SUBMITTED", new Claim("CLM-1", new Money("TWD", 100000), Instant.parse("2026-11-20T02:00:00Z"), "BANK-778", "ev/buyer.png"), null);
        return new OrderView("PO-1", "REQ-1", "E1", "S1", offer, status, null, null, List.of(claim), actions, "OPS");
    }

    @Test
    void submitFreezesFormThenApplyConfirmsAsFiller() throws Exception {
        when(provider.getIfAvailable()).thenReturn(port);
        when(provider.getObject()).thenReturn(port);
        when(port.enabled(PlanPaymentApprovalSubject.SUBJECT_TYPE)).thenReturn(true);
        when(plans.order(any(), eq("PO-1"))).thenReturn(order("SUBMITTED", List.of()));
        assertThat(subject.level()).isEqualTo(ApprovalSubject.Level.FUNDS);
        assertThat(subject.enabled()).isTrue();

        var view = subject.submit("E1", "PO-1", confirm, "OPS_A", "运营甲");
        assertThat(view.status()).isEqualTo("PENDING_APPROVAL");
        verify(plans, never()).confirm(any(), any(), any());
        long revision = PlanPaymentApprovalSubject.revision(order("SUBMITTED", List.of()));
        @SuppressWarnings("unchecked") var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(port).submit(eq(PlanPaymentApprovalSubject.SUBJECT_TYPE), eq("E1:PO-1"), eq(revision), any(), captor.capture());

        // 拟定结果经 OpsKit JSON 往返（数字会变 Integer）
        var json = new ObjectMapper();
        @SuppressWarnings("unchecked") Map<String, Object> frozen = json.readValue(json.writeValueAsString(captor.getValue()), Map.class);
        assertThat(subject.snapshot("E1:PO-1", frozen).facts()).anySatisfy(f -> {
            assertThat(f.label()).isEqualTo("实收（申报 → 核定）");
            assertThat(f.before()).isEqualTo("TWD 1000.00");
            assertThat(f.value()).isEqualTo("TWD 999.00");
        });

        subject.apply("E1:PO-1", revision, frozen);
        verify(plans).confirm(new Actor("OPS_A", "E1", null, true), "PO-1", confirm);
    }

    @Test
    void staleWhenOrderChangedAndReplayAfterConfirmIsNoop() {
        var frozen = PlanPaymentApprovalSubject.proposal(confirm, "OPS_A");
        long revision = PlanPaymentApprovalSubject.revision(order("SUBMITTED", List.of()));
        when(plans.order(any(), eq("PO-1"))).thenReturn(order("CANCELLATION_REQUESTED", List.of()));
        assertThatThrownBy(() -> subject.apply("E1:PO-1", revision, frozen))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo(ApprovalApplyException.STALE));
        when(plans.order(any(), eq("PO-1"))).thenReturn(order("CONFIRMING", List.of(new ActionView("ACT-1", "ACCEPTED", null, "已核对网银"))));
        subject.apply("E1:PO-1", revision, frozen);
        verify(plans, never()).confirm(any(), any(), any());
    }
}
