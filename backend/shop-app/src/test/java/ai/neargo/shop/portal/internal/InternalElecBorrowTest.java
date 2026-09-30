package ai.neargo.shop.portal.internal;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.spi.user.WxAuthPort;
import ai.neargo.shop.spi.user.WxPhonePort;
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
 * 元器件独立账号之后向 ai-shop 借的四件事（ai-key TDD-元器件-独立账号 §2.4）。
 *
 * <p>不起 Spring：要看的是「调了哪个端口、失败时回什么」—— 桩世界里短信与微信都是成功的，
 * 失败那一半在集成测试里走不到。
 */
class InternalElecBorrowTest {

    private static final String KEY = "k";

    private final SmsPort sms = mock(SmsPort.class);
    private final WxAuthPort wxAuth = mock(WxAuthPort.class);
    private final WxPhonePort wxPhone = mock(WxPhonePort.class);
    private final WxSubscribePort wxPort = mock(WxSubscribePort.class);
    private final InternalElecEndpoint ep = new InternalElecEndpoint(null, null, null, null, null, null,
            sms, wxAuth, wxPhone, wxPort, "wxAPP", KEY);

    @Test
    @DisplayName("★★★ 四条都要共享密钥 —— 不带或带错一律 401，端口一个都不碰")
    void allRequireKey() {
        assertThat(ep.smsOtp("x", new ElecInternal.SmsOtpReq("13800000000", "123456")).getStatusCode().value())
                .isEqualTo(401);
        assertThat(ep.wxSession(null, new ElecInternal.WxCodeReq("c")).getStatusCode().value()).isEqualTo(401);
        assertThat(ep.wxPhone("x", new ElecInternal.WxCodeReq("c")).getStatusCode().value()).isEqualTo(401);
        assertThat(ep.wxSend("x", new ElecInternal.WxSendReq("o", "EQ1", "s", "已报价", "p")).getStatusCode().value())
                .isEqualTo(401);
        verify(sms, never()).sendOtp(anyString(), anyString(), anyString(), any());
        verify(wxAuth, never()).codeToSession(anyString());
        verify(wxPort, never()).sendElecQuoted(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("★★ 短信：用 ai-key 给的码投递、用途记 ELEC_LOGIN；通道失败回 sent=false 并带上能不能重试")
    void smsDeliversGivenCode() {
        assertThat(ep.smsOtp(KEY, new ElecInternal.SmsOtpReq("13800000000", "654321")).getBody())
                .isEqualTo(new ElecInternal.SmsOtpResult(true, false));
        verify(sms).sendOtp("13800000000", "654321", "ELEC_LOGIN", null);

        when(sms.sendOtp(eq("13900000000"), anyString(), anyString(), any()))
                .thenThrow(new SmsPort.SmsException("限流", true));
        assertThat(ep.smsOtp(KEY, new ElecInternal.SmsOtpReq("13900000000", "1")).getBody())
                .isEqualTo(new ElecInternal.SmsOtpResult(false, true));
    }

    @Test
    @DisplayName("★★ code2Session：带上虹选的 appid；code 无效回 ok=false 而不是 500")
    void wxSessionCarriesAppId() {
        when(wxAuth.codeToSession("good")).thenReturn(new WxAuthPort.WxSession("OPENID", null));
        when(wxAuth.codeToSession("bad")).thenThrow(new WxAuthPort.WxAuthException("40029"));
        assertThat(ep.wxSession(KEY, new ElecInternal.WxCodeReq("good")).getBody())
                .isEqualTo(new ElecInternal.WxSession(true, "wxAPP", "OPENID", null));
        assertThat(ep.wxSession(KEY, new ElecInternal.WxCodeReq("bad")).getBody().ok()).isFalse();
    }

    @Test
    @DisplayName("★ 取号：没开通直接 ok=false，不去调微信")
    void wxPhoneDisabled() {
        when(wxPhone.enabled()).thenReturn(false);
        assertThat(ep.wxPhone(KEY, new ElecInternal.WxCodeReq("c")).getBody().ok()).isFalse();
        verify(wxPhone, never()).phoneOf(anyString());

        when(wxPhone.enabled()).thenReturn(true);
        when(wxPhone.phoneOf("c")).thenReturn("13800000000");
        assertThat(ep.wxPhone(KEY, new ElecInternal.WxCodeReq("c")).getBody())
                .isEqualTo(new ElecInternal.WxPhone(true, "13800000000"));
    }

    @Test
    @DisplayName("★★★ 发订阅：按 openid 直接发、不经 ai-shop 的额度表；没配模板就不发")
    void wxSendByOpenId() {
        var req = new ElecInternal.WxSendReq("OPENID", "EQ1", "STM32 等 2 项", "已报价", "pkg-elec/pages/rfq/detail");
        when(wxPort.templateId(WxSubscribePort.SCENE_ELEC_QUOTED)).thenReturn("");
        assertThat(ep.wxSend(KEY, req).getBody().sent()).isFalse();
        verify(wxPort, never()).sendElecQuoted(any(), any(), any(), any(), any(), any());

        when(wxPort.templateId(WxSubscribePort.SCENE_ELEC_QUOTED)).thenReturn("TPL");
        assertThat(ep.wxSend(KEY, req).getBody().sent()).isTrue();
        verify(wxPort).sendElecQuoted("OPENID", "EQ1", "STM32 等 2 项", "已报价", "pkg-elec/pages/rfq/detail", null);
    }
}
