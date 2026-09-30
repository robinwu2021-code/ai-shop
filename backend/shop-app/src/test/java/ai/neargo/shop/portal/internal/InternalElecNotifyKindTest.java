package ai.neargo.shop.portal.internal;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.notify.WxSubscribeSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 通知的<b>通道选择</b>：哪种事发订阅消息、哪种只进站内信，标题写什么。
 *
 * <p>不起 Spring、直接构造：集成测试里没有订阅额度，发没发订阅消息返回值都是 false，
 * 「到期提醒不该发」这件事在那边<b>分不出来</b>。这里看的是调用本身。
 */
class InternalElecNotifyKindTest {

    private static final String KEY = "k";

    private final MessageService messages = mock(MessageService.class);
    private final WxSubscribeSender wx = mock(WxSubscribeSender.class);
    private final InternalElecEndpoint ep = new InternalElecEndpoint(null, null, null, null, messages, wx, null, null, null, null, "", KEY);

    @Test
    @DisplayName("★★★ 库存快到期只进站内信、不碰订阅消息 —— 供应商的授权额度要留给「有新求购」")
    void expiringSkipsSubscribe() {
        var r = ep.notifySupplier(KEY, new ElecInternal.SupplierNotice("U1", ElecInternal.KIND_EXPIRING,
                "库存快到期了", "2 行库存最早明天到期", "pkg-elec/pages/stocks/index?filter=EXPIRING", "D1"));
        assertThat(r.getBody().inApp()).isTrue();
        verify(messages).pushTo(anyString(), eq("U1"), anyString(), eq("库存快到期了"), anyString(), anyString(),
                eq("D1"));
        verify(wx, never()).elecQuoted(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("★★ 对照：有新求购照样发订阅消息（量具是活的）")
    void dispatchStillSubscribes() {
        ep.notifySupplier(KEY, new ElecInternal.SupplierNotice("U2", ElecInternal.KIND_DISPATCH, "有新的求购",
                "有一条求购等你报价", "pkg-elec/pages/dispatches/index?status=SENT", "D2"));
        verify(wx).elecQuoted(eq("U2"), anyString(), anyString(), eq("有新求购"), anyString());
    }

    @Test
    @DisplayName("★★★ 买家的四种结果各有各的标题：供应商报价、整行没货不能落进「平台已报价」的兜底")
    void buyerResultTitles() {
        assertThat(titleOf(ElecInternal.RESULT_OFFER)).isEqualTo("询价有新报价");
        assertThat(titleOf(ElecInternal.RESULT_LINE_NO_OFFER)).isEqualTo("询价有一项暂无货源");
        assertThat(titleOf(ElecInternal.RESULT_NO_SOURCE)).isEqualTo("询价暂无货源");
        assertThat(titleOf(ElecInternal.RESULT_QUOTED)).isEqualTo("询价有报价了");
    }

    private String titleOf(String result) {
        MessageService m = mock(MessageService.class);
        InternalElecEndpoint e = new InternalElecEndpoint(null, null, null, null, m, wx, null, null, null, null, "", KEY);
        e.notifyQuoted(KEY, new ElecInternal.QuotedNotice("U3", "R1", result, "LM358", "pkg-elec/pages/rfq/index?rfqNo=R1"));
        var title = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(m).pushTo(anyString(), eq("U3"), anyString(), title.capture(), anyString(), anyString(), anyString());
        return title.getValue();
    }
}
