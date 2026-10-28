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
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PayBizInternalApi.RefundReq;
import ai.neargo.shop.pay.client.PortResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 独立形态的商家端支付客户端（开发计划 M5a.5）：操作人取当前登录的商家账号，门店照传，支付的业务拒绝还原成同一个错误码 */
class RemoteBizPaymentAppServiceTest {

    private final PayBizInternalApi api = mock(PayBizInternalApi.class);
    private final RemoteBizPaymentAppService remote = new RemoteBizPaymentAppService(api);

    @BeforeEach
    void loginAsMerchant() {
        LoginUser m = LoginUser.merchant("MCH-A", "店主甲");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(m, null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("★★★ 每个调用带上当前商家账号与当前门店")
    void operatorAndStoreAreCarried() {
        when(api.disableQrCode("S-1", "QR-1", "MCH-A")).thenReturn(PortResult.ok(null));
        when(api.refunds("S-1", "MCH-A")).thenReturn(PortResult.ok(List.of()));

        remote.disableQrCode("S-1", "QR-1");
        remote.refunds("S-1");

        verify(api).disableQrCode("S-1", "QR-1", "MCH-A");
        verify(api).refunds("S-1", "MCH-A");
    }

    @Test
    @DisplayName("★★★ 支付的业务拒绝还原成同一个错误码（b-app 看到的与内嵌形态一致）")
    void rejectionBecomesBizException() {
        when(api.requestRefund(eq("S-1"), any())).thenReturn(PortResult.rejected("PAY_FORBIDDEN", List.of()));

        assertThatThrownBy(() -> remote.requestRefund("S-1", "PYO-1", 100L, null, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAY_FORBIDDEN));
        verify(api).requestRefund("S-1", new RefundReq("MCH-A", "PYO-1", 100L, null, null));
    }
}
