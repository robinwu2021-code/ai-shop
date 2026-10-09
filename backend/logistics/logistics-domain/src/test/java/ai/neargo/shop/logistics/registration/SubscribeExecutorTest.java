package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort.ShipmentSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 取快照 + 按链订阅（TDD-物流模块 AC1 / AC5 / AC7 / AC13）。 */
class SubscribeExecutorTest {

    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final ShipmentSourcePort source = mock(ShipmentSourcePort.class);
    private final ChannelRouter router = mock(ChannelRouter.class);
    private final LogisticsProperties props = new LogisticsProperties();
    private final List<String> calls = new ArrayList<>();

    private SubscribeExecutor executor() {
        LogisticsProperties keyed = new LogisticsProperties();
        keyed.setPhoneKey("k");
        return new SubscribeExecutor(waybills, source, router, new PhoneCipher(keyed), props, new ObjectMapper());
    }

    private LgsWaybill pending() {
        LgsWaybill w = new LgsWaybill();
        w.setId(1L);
        w.setShipmentNo("SH1");
        w.setBizRef("SUB1");
        w.setCarrier("YTO");
        w.setWaybillNo("YT1");
        w.setStatus("CREATED");
        w.setSubState(LgsWaybill.SUB_PENDING);
        w.setSubAttempts(0);
        when(waybills.selectOne(any())).thenReturn(w);
        return w;
    }

    private void source(ShipmentSourcePort.WxKey wx) {
        when(source.sourceOf("SUB1")).thenReturn(Optional.of(new ShipmentSource("SUB1", "O1", "E1", "ST1", "YTO", "YT1",
                "张三", "13800138000", "浙江省 杭州市", wx,
                List.of(new ShipmentSourcePort.GoodsBrief("盐", "img")), "/pages/order/index?orderNo=SUB1")));
    }

    private TrackingSubscriber sub(String name, ChannelOutcome outcome) {
        TrackingSubscriber s = mock(TrackingSubscriber.class);
        when(s.channel()).thenReturn(name);
        when(s.subscribe(any())).thenAnswer(inv -> {
            calls.add(name);
            return outcome;
        });
        return s;
    }

    private static SysOutbox event() {
        SysOutbox e = new SysOutbox();
        e.setPayload("{\"shipmentNo\":\"SH1\"}");
        return e;
    }

    private List<LgsWaybill> patches() {
        ArgumentCaptor<LgsWaybill> c = ArgumentCaptor.forClass(LgsWaybill.class);
        verify(waybills, atLeastOnce()).updateById(c.capture());
        return c.getAllValues();
    }

    private LgsWaybill last() {
        List<LgsWaybill> p = patches();
        return p.get(p.size() - 1);
    }

    @Test
    @DisplayName("★★★ 总开关关着：取了快照、不调任何渠道，停在 PENDING")
    void disabledTakesSnapshotOnly() {
        pending();
        source(new ShipmentSourcePort.WxKey("TX1", "O1", "openid-1"));
        props.setSubscribeEnabled(false);

        executor().consume(event());

        LgsWaybill snap = patches().get(0);
        assertThat(snap.getProfile()).isEqualTo(LgsWaybill.PROFILE_WX);
        assertThat(snap.getBindState()).isEqualTo(LgsWaybill.BIND_WAITING);
        assertThat(snap.getReceiverPhoneLast4()).isEqualTo("8000");
        assertThat(snap.getReceiverPhoneEnc()).isNotNull().doesNotContain("13800138000");
        assertThat(snap.getSubState()).as("开关关着不动订阅状态").isNull();
        verify(router, org.mockito.Mockito.never()).subscribers(anyString(), anyString());
    }

    @Test
    @DisplayName("★★★ 微信键缺一样（没有 openid）→ SELF：给半个只会让换 token 失败")
    void missingOpenidIsSelf() {
        pending();
        source(null);
        props.setSubscribeEnabled(false);
        executor().consume(event());
        assertThat(patches().get(0).getProfile()).isEqualTo(LgsWaybill.PROFILE_SELF);
        assertThat(patches().get(0).getBindState()).isEqualTo(LgsWaybill.BIND_NA);
    }

    @Test
    @DisplayName("★★★ 前一家不可重试 → 换下一家；成功即停，记受理渠道")
    void fatalFallsToNext() {
        pending();
        source(null);
        props.setSubscribeEnabled(true);
        TrackingSubscriber yto = sub("yto", ChannelOutcome.fatal("AUTH", "凭据错"));
        TrackingSubscriber kd = sub("kuaidi100", ChannelOutcome.ok("200", null));
        when(router.subscribers(any(), any())).thenReturn(List.of(yto, kd));
        when(router.codeOf(any(), any())).thenReturn(Optional.of("yuantong"));

        executor().consume(event());

        assertThat(calls).containsExactly("yto", "kuaidi100");
        assertThat(last().getSubState()).isEqualTo(LgsWaybill.SUB_DONE);
        assertThat(last().getSubChannel()).isEqualTo("kuaidi100");
        assertThat(last().getKd100SubCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 可重试 → 抛出，交 outbox 退避重投（不自己再写一套重试）")
    void retryableThrows() {
        pending();
        source(null);
        props.setSubscribeEnabled(true);
        TrackingSubscriber kd = sub("kuaidi100", ChannelOutcome.retryable("500", "x"));
        when(router.subscribers(any(), any())).thenReturn(List.of(kd));
        when(router.codeOf(any(), any())).thenReturn(Optional.of("yuantong"));
        assertThatThrownBy(() -> executor().consume(event())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★★ 没有任何可用渠道 → FATAL，运营看得见（不悄悄停在 PENDING）")
    void noChannelIsFatal() {
        pending();
        source(null);
        props.setSubscribeEnabled(true);
        when(router.subscribers(any(), any())).thenReturn(List.of());
        executor().consume(event());
        assertThat(last().getSubState()).isEqualTo(LgsWaybill.SUB_FATAL);
        assertThat(last().getSubError()).contains("没有可用的订阅渠道");
    }

    @Test
    @DisplayName("★★ 快递100 本单号本月已订 4 次：不去撞上限，直接跳过")
    void kd100MonthlyLimit() {
        LgsWaybill w = pending();
        w.setKd100SubMonth(YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM")));
        w.setKd100SubCount(4);
        source(null);
        props.setSubscribeEnabled(true);
        TrackingSubscriber kd = sub("kuaidi100", ChannelOutcome.ok("200", null));
        when(router.subscribers(any(), any())).thenReturn(List.of(kd));
        executor().consume(event());
        assertThat(calls).isEmpty();
        assertThat(last().getSubState()).isEqualTo(LgsWaybill.SUB_FATAL);
    }
}
