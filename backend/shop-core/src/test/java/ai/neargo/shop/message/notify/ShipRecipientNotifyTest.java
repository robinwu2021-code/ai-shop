package ai.neargo.shop.message.notify;

import ai.neargo.shop.link.ShortLinkService;
import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.trade.SubOrderBuyerPort;
import ai.neargo.shop.trade.track.ShipTrackToken;
import ai.neargo.shop.trade.port.ShipTrackPortImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 发货短信这条链的承诺（TDD-收件人物流触达 §2）：
 * 没收件人不发、有收件人则「签票→短链(指 H5 看件页)→短信」、任何一步失败都吞掉。
 *
 * <p>短链 target **固定指 H5 看件页**（去小程序交给 H5 页上的按钮，见 MpTrackController）。
 */
@DisplayName("发货触达收件人 ShipRecipientNotify")
class ShipRecipientNotifyTest {

    private ShortLinkService shortLink;
    private SmsPort sms;
    private SubOrderBuyerPort buyer;
    private ShipRecipientNotify notify;

    @BeforeEach
    void setUp() {
        shortLink = mock(ShortLinkService.class);
        sms = mock(SmsPort.class);
        buyer = mock(SubOrderBuyerPort.class);
        when(shortLink.shorten(anyString(), anyString(), anyString(), any()))
                .thenReturn("https://s.hxmall.top/ABC1234");
        when(sms.sendShipToRecipient(anyString(), anyString())).thenReturn(SendResult.none());
        notify = new ShipRecipientNotify(new ShipTrackPortImpl(new ShipTrackToken("k", 30)),
                shortLink, sms, buyer,
                "https://www.hxmall.top/c/?t=", 30);
    }

    @Test
    @DisplayName("没有收件人（自提单）→ 一条短信都不发")
    void noReceiverNoSms() {
        when(buyer.receiverPhoneOf("SUB-A")).thenReturn(Optional.empty());
        notify.notify("SUB-A");
        verifyNoInteractions(sms);
        verifyNoInteractions(shortLink);
    }

    @Test
    @DisplayName("★ 有收件人 → 建短链并把短链发给收件人号")
    void sendsShortLinkToReceiver() {
        when(buyer.receiverPhoneOf("SUB-A")).thenReturn(Optional.of("13800000000"));
        notify.notify("SUB-A");
        verify(sms).sendShipToRecipient(eq("13800000000"), eq("https://s.hxmall.top/ABC1234"));
    }

    @Test
    @DisplayName("★ 短链目标永远是 H5 看件页（带 token）—— 去小程序交给 H5 页")
    void targetIsH5TrackPage() {
        when(buyer.receiverPhoneOf("SUB-A")).thenReturn(Optional.of("13800000000"));
        notify.notify("SUB-A");
        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(shortLink).shorten(target.capture(), anyString(), eq("SUB-A"), any());
        assertThat(target.getValue())
                .startsWith("https://www.hxmall.top/c/?t=");
    }

    @Test
    @DisplayName("★ 短信通道抛异常也吞掉 —— 不冒泡到 outbox 消费者")
    void swallowsSmsFailure() {
        when(buyer.receiverPhoneOf("SUB-A")).thenReturn(Optional.of("13800000000"));
        when(sms.sendShipToRecipient(anyString(), anyString()))
                .thenThrow(new SmsPort.SmsException("boom", false));
        notify.notify("SUB-A");
        verify(sms).sendShipToRecipient(anyString(), anyString());
    }
}
