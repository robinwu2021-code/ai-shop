package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.WaybillTokenBinder;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.CarrierCodeBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 换 waybill_token：前置条件与四种结局（TDD-物流模块 AC2 / AC3）。 */
class WxBindTest {

    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final WaybillTokenBinder binder = mock(WaybillTokenBinder.class);
    private final CarrierCodeBook codes = mock(CarrierCodeBook.class);
    private final LogisticsProperties props = new LogisticsProperties();

    private static LgsWaybill ready() {
        LgsWaybill w = new LgsWaybill();
        w.setId(9L);
        w.setShipmentNo("SH9");
        w.setBizRef("SUB9");
        w.setCarrier("STO");
        w.setWaybillNo("773");
        w.setProfile(LgsWaybill.PROFILE_WX);
        w.setBindState(LgsWaybill.BIND_WAITING);
        w.setWxUploadedAt(1L);
        w.setStatus("PICKED_UP");
        w.setWxOpenid("o1");
        w.setWxTransId("tx1");
        return w;
    }

    @Test
    @DisplayName("★★★ 前置条件：没上传微信、还没揽收、不是微信支付单、已换过 —— 一律不调")
    void policy() {
        assertThat(WxBindPolicy.ready(ready(), false)).isTrue();
        LgsWaybill notUploaded = ready();
        notUploaded.setWxUploadedAt(null);
        assertThat(WxBindPolicy.ready(notUploaded, false)).as("微信不认识这笔交易").isFalse();
        LgsWaybill notPicked = ready();
        notPicked.setStatus("CREATED");
        assertThat(WxBindPolicy.ready(notPicked, false)).as("揽收前微信一定查不到（9300559）").isFalse();
        assertThat(WxBindPolicy.ready(notPicked, true)).as("on-ship 不等揽收").isTrue();
        LgsWaybill self = ready();
        self.setProfile(LgsWaybill.PROFILE_SELF);
        assertThat(WxBindPolicy.ready(self, false)).as("线下付款单不调任何微信物流接口").isFalse();
        LgsWaybill done = ready();
        done.setDisplayToken("tk");
        assertThat(WxBindPolicy.ready(done, false)).isFalse();
    }

    private WxBindExecutor executor() {
        when(binder.channel()).thenReturn("wx");
        when(binder.available()).thenReturn(true);
        when(codes.codeOf(any(), any())).thenReturn(Optional.of("STO"));
        return new WxBindExecutor(waybills, List.of(binder), codes, new PhoneCipher(props), props, new ObjectMapper());
    }

    private static SysOutbox event() {
        SysOutbox e = new SysOutbox();
        e.setPayload("{\"shipmentNo\":\"SH9\"}");
        return e;
    }

    @Test
    @DisplayName("★★★ 成功 → DONE + token")
    void ok() {
        when(waybills.selectOne(any())).thenReturn(ready());
        WxBindExecutor x = executor();
        when(binder.bind(any())).thenReturn(ChannelOutcome.ok("0", "tk-1"));
        x.consume(event());
        ArgumentCaptor<LgsWaybill> c = ArgumentCaptor.forClass(LgsWaybill.class);
        verify(waybills).updateById(c.capture());
        assertThat(c.getValue().getDisplayToken()).isEqualTo("tk-1");
        assertThat(c.getValue().getBindState()).isEqualTo(LgsWaybill.BIND_DONE);
    }

    @Test
    @DisplayName("★★★ 9300559 微信还没收录 → 保持 WAITING、不重试（下一条推送再触发）")
    void notReadyKeepsWaiting() {
        when(waybills.selectOne(any())).thenReturn(ready());
        WxBindExecutor x = executor();
        when(binder.bind(any())).thenReturn(ChannelOutcome.notReady("9300559", "运单不存在"));
        x.consume(event());   // 不抛
        verify(waybills, never()).updateById(any(LgsWaybill.class));
    }

    @Test
    @DisplayName("★★ 手机号错 → FATAL 记原因；超限 → 抛给 outbox 退避")
    void fatalAndRetryable() {
        when(waybills.selectOne(any())).thenReturn(ready());
        WxBindExecutor x = executor();
        when(binder.bind(any())).thenReturn(ChannelOutcome.fatal("9300561", "收件人手机号错误"));
        x.consume(event());
        ArgumentCaptor<LgsWaybill> c = ArgumentCaptor.forClass(LgsWaybill.class);
        verify(waybills).updateById(c.capture());
        assertThat(c.getValue().getBindState()).isEqualTo(LgsWaybill.BIND_FATAL);
        assertThat(c.getValue().getBindError()).contains("9300561");

        when(binder.bind(any())).thenReturn(ChannelOutcome.retryable("9300513", "超限"));
        assertThatThrownBy(() -> x.consume(event())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★★ 前置条件不满足时连微信都不碰（事件可能在状态变化后才到）")
    void notReadyNoCall() {
        LgsWaybill w = ready();
        w.setWxUploadedAt(null);
        when(waybills.selectOne(any())).thenReturn(w);
        WxBindExecutor x = executor();
        x.consume(event());
        verify(binder, never()).bind(any());
    }
}
