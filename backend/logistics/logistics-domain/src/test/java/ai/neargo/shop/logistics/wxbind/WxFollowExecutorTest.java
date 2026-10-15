package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.WaybillFollower;
import ai.neargo.shop.logistics.entity.LgsChannelSubscription;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.registration.ChannelSubscriptionStore;
import ai.neargo.shop.logistics.routing.CarrierCodeBook;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxFollowExecutorTest {
    private final WaybillMapper waybills = mock(WaybillMapper.class);
    private final ShipmentSourcePort source = mock(ShipmentSourcePort.class);
    private final ChannelSubscriptionStore store = mock(ChannelSubscriptionStore.class);
    private final CarrierCodeBook codes = mock(CarrierCodeBook.class);
    private final WaybillFollower gateway = mock(WaybillFollower.class);
    private final WxFollowResultWriter writer = mock(WxFollowResultWriter.class);

    private WxFollowExecutor executor() {
        return new WxFollowExecutor(waybills, source, store, codes, gateway, writer, new ObjectMapper());
    }

    private void current() {
        LgsWaybill waybill = new LgsWaybill();
        waybill.setId(1L);
        waybill.setShipmentNo("SH1");
        waybill.setBizRef("SUB1");
        waybill.setSourceShipmentNo("OS1");
        waybill.setSourceShipmentVersion(1L);
        waybill.setCarrier("YTO");
        waybill.setWaybillNo("YT1");
        when(waybills.selectOne(any())).thenReturn(waybill);
        when(source.sourceOf("SUB1")).thenReturn(Optional.of(new ShipmentSourcePort.ShipmentSource(
                "SUB1", "O1", "E1", "S1", "YTO", "YT1", "张三", "13800138000", "浙江",
                new ShipmentSourcePort.WxKey("4201", "M1", "buyer"),
                List.of(new ShipmentSourcePort.GoodsBrief("茶叶", "https://example.test/a.png")),
                "/pages/order/index?orderNo=O1", null, "OS1", 1L)));
        LgsChannelSubscription row = new LgsChannelSubscription();
        row.setState("PENDING");
        when(store.ensurePending(waybill, "wx-follow")).thenReturn(row);
        when(codes.codeOf("YTO", "wx")).thenReturn(Optional.of("YTO"));
        when(gateway.available()).thenReturn(true);
    }

    @Test
    void savesFollowTokenAndImmediateStatusForCurrentShipment() {
        current();
        when(gateway.follow(any())).thenReturn(ChannelOutcome.ok("0", "token-1"));
        TraceResult signed = new TraceResult("YT1", "YTO", TraceStatus.SIGNED, "wx-follow", List.of());
        when(gateway.queryFollow("token-1", "YTO", "YT1")).thenReturn(Optional.of(signed));

        assertThat(executor().followNow("SH1").kind()).isEqualTo(ChannelOutcome.Kind.OK);

        ArgumentCaptor<WaybillFollower.FollowCmd> cmd = ArgumentCaptor.forClass(WaybillFollower.FollowCmd.class);
        verify(gateway).follow(cmd.capture());
        assertThat(cmd.getValue().openid()).isEqualTo("buyer");
        assertThat(cmd.getValue().transId()).isEqualTo("4201");
        verify(writer).recordSuccess(any(LgsWaybill.class), eq("token-1"), eq(Optional.of(signed)));
    }

    @Test
    void missingWechatPurchaseDoesNotStopOtherChannel() {
        current();
        when(source.sourceOf("SUB1")).thenReturn(Optional.of(new ShipmentSourcePort.ShipmentSource(
                "SUB1", "O1", "E1", "S1", "YTO", "YT1", "张三", "13800138000", "浙江",
                null, List.of(), "/pages/order/index?orderNo=O1", null, "OS1", 1L)));

        assertThat(executor().followNow("SH1").kind()).isEqualTo(ChannelOutcome.Kind.FATAL);
        verify(store).mark("SH1", "OS1:1", "wx-follow", "NOT_ELIGIBLE", null, "缺少微信支付交易信息");
    }
}
