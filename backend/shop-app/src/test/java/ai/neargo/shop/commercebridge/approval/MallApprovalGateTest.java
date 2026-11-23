package ai.neargo.shop.commercebridge.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.commerce.api.common.ActorContext;
import ai.neargo.commerce.api.common.Money;
import ai.neargo.commerce.starter.http.OpsApprovalGatePort;
import ai.neargo.shop.paybridge.approval.PayApprovalSupport;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** T9a / T9b · 商城资金动作「审批通过 → 运营再点执行」门禁。 */
@DisplayName("商城审批门禁：首次点击送审；审批中等待；同一内容放行后才执行；内容变了重新送审")
class MallApprovalGateTest {

    /** 内存里的商城对象：state 变 = 业务被改过；pending=false = 已执行。 */
    static class FakeSubject extends MallGateSubject {
        String state = "v1:REQUESTED";
        boolean pending = true;
        FakeSubject() { super(null); }
        @Override public String subjectType() { return "shop.mall_refund"; }
        @Override public String processCode() { return "shop_mall_refund"; }
        @Override public String processName() { return "商城退款"; }
        @Override public String approverPerm() { return "commerce:refund:review"; }
        @Override String action() { return OpsApprovalGatePort.MALL_REFUND_APPROVE; }
        @Override String state(Ref ref) { return state; }
        @Override boolean awaiting(Ref ref) { return pending; }
        @Override java.util.Set<String> scopes(Ref r) { return java.util.Set.of(); }
        @Override public Snapshot snapshot(String ref, Map<String, Object> p) { return null; }
    }

    record Approve(String approvalDigest, String reviewQuoteNo, Money cashAmount, Money restoreCredit, String reviewEvidence) {}

    final FakeSubject subject = new FakeSubject();
    final ApprovalPort port = mock(ApprovalPort.class);
    @SuppressWarnings("unchecked") final ObjectProvider<ApprovalPort> provider = mock(ObjectProvider.class);
    final MallApprovalGate gate;
    final ActorContext operator = mock(ActorContext.class);

    MallApprovalGateTest() {
        when(provider.getIfAvailable()).thenReturn(port);
        when(provider.getObject()).thenReturn(port);
        when(port.enabled("shop.mall_refund")).thenReturn(true);
        when(port.latest(anyString(), anyString())).thenReturn(Optional.empty());
        when(operator.tenantNo()).thenReturn("MAIN");
        when(operator.principalNo()).thenReturn("OPS_A");
        gate = new MallApprovalGate(List.of(subject), provider, new PayApprovalSupport(provider, mock(PlatformTransactionManager.class)));
    }

    Optional<Object> click(long cash) {
        return gate.hold(OpsApprovalGatePort.MALL_REFUND_APPROVE, operator, "S1", "O1", "R1",
                new Approve("dg", "Q1", new Money("TWD", cash), new Money("TWD", 0), "核过"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void firstClickSubmitsThenWaitsThenReleasesSameContent() {
        var first = click(500);
        assertThat((Map<String, Object>) first.orElseThrow()).containsEntry("approvalStatus", "SUBMITTED").containsEntry("subjectRef", "MAIN:S1:O1:R1");
        var captor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(port).submit(eq("shop.mall_refund"), eq("MAIN:S1:O1:R1"), captor.capture(), any(), anyMap());
        long rev = captor.getValue();

        when(port.latest("shop.mall_refund", "MAIN:S1:O1:R1")).thenReturn(Optional.of(new ApprovalPort.Link("shop.mall_refund", "MAIN:S1:O1:R1", rev, "shop_mall_refund", null, null, "PENDING", null)));
        assertThat((Map<String, Object>) click(500).orElseThrow()).containsEntry("approvalStatus", "PENDING");

        when(port.latest("shop.mall_refund", "MAIN:S1:O1:R1")).thenReturn(Optional.of(new ApprovalPort.Link("shop.mall_refund", "MAIN:S1:O1:R1", rev, "shop_mall_refund", null, null, "APPLIED", null)));
        assertThat(click(500)).isEmpty(); // 放行：进入商城，以运营本人会话执行

        assertThat(click(600)).isPresent(); // 改了金额 = 另一份审批
        verify(port, times(2)).submit(anyString(), anyString(), anyLong(), any(), anyMap());

        subject.pending = false; // 已执行：重放交给商城
        assertThat(click(500)).isEmpty();
    }

    @Test
    void approvalNodeReleasesOnlyWhenNothingChanged() {
        var proposal = MallGateSubject.proposal(new Approve("dg", "Q1", new Money("TWD", 500), new Money("TWD", 0), "核过"));
        var ref = MallGateSubject.Ref.parse("MAIN:S1:O1:R1");
        long rev = subject.revision(ref, proposal);
        subject.apply("MAIN:S1:O1:R1", rev, proposal);
        subject.state = "v2:REQUESTED";
        assertThatThrownBy(() -> subject.apply("MAIN:S1:O1:R1", rev, proposal))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo(ApprovalApplyException.STALE));
    }

    @Test
    void gateOffWhenApprovalNotEnabled() {
        when(port.enabled("shop.mall_refund")).thenReturn(false);
        assertThat(click(500)).isEmpty();
        verify(port, never()).submit(anyString(), anyString(), anyLong(), any(), anyMap());
    }
}
