package ai.neargo.shop.portal.internal;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.spi.notify.SmsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 元器件独立账号之后向 ai-shop 借的四件事（ai-hxkey TDD-元器件-独立账号 §2.4）。
 *
 * <p>不起 Spring：要看的是「调了哪个端口、失败时回什么」—— 桩世界里短信与微信都是成功的，
 * 失败那一半在集成测试里走不到。
 */
class InternalElecBorrowTest {

    private static final String KEY = "k";

    private final SmsPort sms = mock(SmsPort.class);
    private final InternalElecEndpoint ep = new InternalElecEndpoint(null, null, null, sms, KEY);

    @Test
    @DisplayName("★★★ 短信代办要共享密钥 —— 不带或带错一律 401，不碰短信通道")
    void allRequireKey() {
        assertThat(ep.smsOtp("x", new ElecInternal.SmsOtpReq("13800000000", "123456")).getStatusCode().value())
                .isEqualTo(401);
        verify(sms, never()).sendOtp(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("★★ 短信：用 ai-hxkey 给的码投递、用途记 ELEC_LOGIN；通道失败回 sent=false 并带上能不能重试")
    void smsDeliversGivenCode() {
        assertThat(ep.smsOtp(KEY, new ElecInternal.SmsOtpReq("13800000000", "654321")).getBody())
                .isEqualTo(new ElecInternal.SmsOtpResult(true, false));
        verify(sms).sendOtp("13800000000", "654321", "ELEC_LOGIN", null);

        when(sms.sendOtp(eq("13900000000"), anyString(), anyString(), any()))
                .thenThrow(new SmsPort.SmsException("限流", true));
        assertThat(ep.smsOtp(KEY, new ElecInternal.SmsOtpReq("13900000000", "1")).getBody())
                .isEqualTo(new ElecInternal.SmsOtpResult(false, true));
    }
}
