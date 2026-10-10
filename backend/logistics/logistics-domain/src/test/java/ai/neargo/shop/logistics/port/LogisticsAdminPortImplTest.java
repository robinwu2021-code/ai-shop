package ai.neargo.shop.logistics.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.event.WaybillRegistered;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.CarrierCodeMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.routing.CarrierCodeBook;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.logistics.wxbind.WxBindRequested;
import ai.neargo.shop.spi.logistics.LogisticsAdminPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 运营重放的几道闸（TDD-物流模块 O3：30013 / 30014 / 30015 / 10400 / 范围外 10404）。 */
class LogisticsAdminPortImplTest {

    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final ChannelRouter router = mock(ChannelRouter.class);
    private final OutboxEventBus events = mock(OutboxEventBus.class);
    private final LogisticsAdminPortImpl admin = new LogisticsAdminPortImpl(waybills, mock(WaybillNodeMapper.class),
            mock(CarrierCodeMapper.class), mock(CarrierCodeBook.class), router, List.of(),
            new LogisticsProperties(), events);

    private LgsWaybill stored(String status, String profile) {
        LgsWaybill w = new LgsWaybill();
        w.setId(5L);
        w.setShipmentNo("SH5");
        w.setCarrier("STO");
        w.setEntityNo("E1");
        w.setStatus(status);
        w.setProfile(profile);
        when(waybills.selectOne(any())).thenReturn(w);
        return w;
    }

    private static TrackingSubscriber subscriber(String channel) {
        TrackingSubscriber s = mock(TrackingSubscriber.class);
        when(s.channel()).thenReturn(channel);
        return s;
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BizException) t).errorCode();
    }

    @Test
    @DisplayName("★★★ 已签收 / 已作废 → 30014，什么都不发")
    void terminalIsRejected() {
        stored(WaybillStatus.DELIVERED, LgsWaybill.PROFILE_SELF);

        assertThatThrownBy(() -> admin.replay("SH5", LogisticsAdminPort.REPLAY_SUBSCRIBE, null, null))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.WAYBILL_TERMINAL));
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("★★★ 点名的渠道不可用 → 30015，原因带出来（与渠道总览同一句）")
    void unavailableChannelSaysWhy() {
        stored(WaybillStatus.IN_TRANSIT, LgsWaybill.PROFILE_SELF);
        when(router.subscribeBlocker("yto", "STO")).thenReturn(Optional.of("推送不可用，订阅随之不可用"));

        assertThatThrownBy(() -> admin.replay("SH5", LogisticsAdminPort.REPLAY_SUBSCRIBE, "yto", null))
                .satisfies(t -> {
                    assertThat(codeOf(t)).isEqualTo(ErrorCode.LOGISTICS_CHANNEL_UNAVAILABLE);
                    assertThat(((BizException) t).args()).containsExactly("推送不可用，订阅随之不可用");
                });
    }

    @Test
    @DisplayName("★★★ 快递100 本单号本月已订 4 次 → 30013（同一单号每月最多 4 次是渠道的硬限制）")
    void kuaidi100MonthlyLimit() {
        LgsWaybill w = stored(WaybillStatus.IN_TRANSIT, LgsWaybill.PROFILE_SELF);
        w.setKd100SubMonth(YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM")));
        w.setKd100SubCount(4);
        when(router.subscribeBlocker("kuaidi100", "STO")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> admin.replay("SH5", LogisticsAdminPort.REPLAY_SUBSCRIBE, "kuaidi100", null))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.WAYBILL_SUBSCRIBE_LIMIT));

        // 不点名、而链上只剩快递100 时同样拦 —— 放过去也只会在执行时判死
        TrackingSubscriber kd = subscriber("kuaidi100");
        TrackingSubscriber yto = subscriber("yto");
        when(router.subscribers(any(), any())).thenReturn(List.of(kd));
        assertThatThrownBy(() -> admin.replay("SH5", LogisticsAdminPort.REPLAY_SUBSCRIBE, null, null))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.WAYBILL_SUBSCRIBE_LIMIT));

        // 链上还有别家：放行（执行时跳过快递100、换下一家）
        when(router.subscribers(any(), any())).thenReturn(List.of(kd, yto));
        admin.replay("SH5", LogisticsAdminPort.REPLAY_SUBSCRIBE, null, null);
        verify(events).publish(any(WaybillRegistered.class));
    }

    @Test
    @DisplayName("★★ 受理 → 发带渠道名的登记事件，执行端按点名的那家订")
    void acceptedCarriesForcedChannel() {
        stored(WaybillStatus.IN_TRANSIT, LgsWaybill.PROFILE_SELF);
        when(router.subscribeBlocker("yto", "STO")).thenReturn(Optional.empty());

        admin.replay("SH5", LogisticsAdminPort.REPLAY_SUBSCRIBE, "yto", null);

        ArgumentCaptor<DomainEvent> c = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(c.capture());
        assertThat(c.getValue()).isEqualTo(new WaybillRegistered("SH5", "yto"));
    }

    @Test
    @DisplayName("★★ 线下付款单重换 token → 10400（不调任何微信物流接口，AC5）；微信支付单 → 发换 token 事件")
    void wxBindOnlyForWxOrders() {
        stored(WaybillStatus.IN_TRANSIT, LgsWaybill.PROFILE_SELF);
        assertThatThrownBy(() -> admin.replay("SH5", LogisticsAdminPort.REPLAY_WX_BIND, null, null))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.BAD_REQUEST));

        stored(WaybillStatus.IN_TRANSIT, LgsWaybill.PROFILE_WX);
        admin.replay("SH5", LogisticsAdminPort.REPLAY_WX_BIND, null, null);
        verify(events).publish(new WxBindRequested("SH5"));
    }

    @Test
    @DisplayName("★★★ 不在运营的商家范围里 → 10404，与不存在不区分（防探测）")
    void outOfScopeLooksMissing() {
        stored(WaybillStatus.IN_TRANSIT, LgsWaybill.PROFILE_SELF);

        assertThatThrownBy(() -> admin.replay("SH5", LogisticsAdminPort.REPLAY_SUBSCRIBE, null, Set.of("E9")))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> admin.changeWaybill("SH5", null, "N1", "打错了", Set.of()))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("★★ 配了范围却一个商家都没有 → 列表为空，不是全部（fail-closed）")
    void emptyScopeSeesNothing() {
        var page = admin.list(new LogisticsAdminPort.ShipmentQuery(null, null, null, null, null, null, null,
                Set.of(), 1, 20));

        assertThat(page.total()).isZero();
        verify(waybills, never()).selectPage(any(), any());
    }
}
