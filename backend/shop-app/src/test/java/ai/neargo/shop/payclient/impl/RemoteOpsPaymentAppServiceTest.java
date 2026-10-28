package ai.neargo.shop.payclient.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayOpsInternalApi;
import ai.neargo.shop.pay.client.PayOpsInternalApi.OperatorReq;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 独立形态的支付运营客户端（开发计划 M5a.3）：操作人取当前登录的运营员工号，支付的业务拒绝还原成同一个错误码 */
class RemoteOpsPaymentAppServiceTest {

    private final PayOpsInternalApi api = mock(PayOpsInternalApi.class);
    private final AuditLogPort audit = mock(AuditLogPort.class);
    private final RemoteOpsPaymentAppService remote = new RemoteOpsPaymentAppService(api, audit);

    @BeforeEach
    void loginAsOperator() {
        LoginUser op = LoginUser.operator("OPS-B", "运营乙", List.of(), List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(op, null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("★★★ 写操作带上当前运营员工号")
    void operatorIsTheLoggedInStaff() {
        when(api.resendNotify(eq(9L), any())).thenReturn(PortResult.ok(null));

        remote.resendNotify(9L);

        verify(api).resendNotify(9L, new OperatorReq("OPS-B", null, null, null));
    }

    @Test
    @DisplayName("★★★ 支付的业务拒绝还原成同一个错误码（前端看到的与内嵌形态一致）")
    void rejectionBecomesBizException() {
        when(api.reviewRefund(eq("PYR-1"), any())).thenReturn(PortResult.rejected("PAY_FORBIDDEN", List.of()));

        assertThatThrownBy(() -> remote.reviewRefund("PYR-1", true, null)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAY_FORBIDDEN));
    }
}
