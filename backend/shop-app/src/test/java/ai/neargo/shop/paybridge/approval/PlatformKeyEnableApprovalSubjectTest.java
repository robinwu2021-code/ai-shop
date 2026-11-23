package ai.neargo.shop.paybridge.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.KeyVersionRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.PlatformKeyRow;
import ai.neargo.shop.payclient.OpsPaymentAppService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T6 · 平台密钥启用接入审批（方案-第一批审批工作流 T6）。 */
@DisplayName("平台密钥审批：执行节点以审批服务身份启用；修订随生效版本与状态变；申请内容带指纹改前改后")
class PlatformKeyEnableApprovalSubjectTest {

    final OpsPaymentAppService keys = mock(OpsPaymentAppService.class);

    void state(int current, String status) {
        when(keys.platformKeys()).thenReturn(List.of(new PlatformKeyRow(7, "PLATFORM", 0, "OUT", "SIGN", "微信商户密钥", "WECHAT", "ACTIVE", current, null)));
        when(keys.platformKeyVersions(7)).thenReturn(List.of(
                new KeyVersionRow(1, current == 1 ? "ACTIVE" : "ROTATING", "fp-old", "S1", "E1"),
                new KeyVersionRow(2, status, "fp-new", "S2", "E1")));
    }

    @Test
    void enablesAsApprovalServiceWhenUnchanged() {
        state(1, "VERIFIED");
        var subject = new PlatformKeyEnableApprovalSubject(keys);
        assertThat(subject.level()).isEqualTo(ApprovalSubject.Level.FUNDS);
        long revision = subject.revision(7, 2);
        assertThat(subject.snapshot("7:2").facts()).anySatisfy(f -> {
            assertThat(f.label()).isEqualTo("指纹");
            assertThat(f.before()).isEqualTo("fp-old");
            assertThat(f.value()).isEqualTo("fp-new");
        });
        var actor = new AtomicReference<String>();
        doAnswer(i -> { actor.set(SecurityUtils.currentUserNo()); return null; }).when(keys).enablePlatformKey(7, 2);
        subject.apply("7:2", revision);
        verify(keys).enablePlatformKey(7, 2);
        assertThat(actor.get()).isEqualTo("OPSKIT"); // 不是录入人 E1：密钥模块两人控制照常满足
    }

    @Test
    void staleWhenAnotherVersionWasActivatedOrStatusChanged() {
        state(1, "VERIFIED");
        var subject = new PlatformKeyEnableApprovalSubject(keys);
        long revision = subject.revision(7, 2);
        state(3, "VERIFIED");
        assertThatThrownBy(() -> subject.apply("7:2", revision))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo(ApprovalApplyException.STALE));
        state(2, "ACTIVE");
        subject.apply("7:2", revision); // 已启用：重投视为成功
        verify(keys, never()).enablePlatformKey(anyLong(), anyInt());
    }
}
