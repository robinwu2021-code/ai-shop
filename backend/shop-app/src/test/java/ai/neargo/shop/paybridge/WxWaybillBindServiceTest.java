package ai.neargo.shop.paybridge;

import ai.neargo.shop.fulfillment.service.LogisticsService;
import ai.neargo.shop.fulfillment.service.LogisticsService.WxBindTarget;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.PaymentLedgerService.PaidPayment;
import ai.neargo.shop.spi.logistics.TraceDisplay.DisplayPayload;
import ai.neargo.shop.spi.logistics.TraceDisplay.ShipmentCtx;
import ai.neargo.shop.spi.logistics.TraceDisplay.Surface;
import ai.neargo.shop.spi.logistics.TraceDisplayPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信 waybill_token 绑定（TDD-物流轨迹多渠道 §2.3）。证两件事：
 * 付款人 openid + 微信交易号齐、微信换到 token → 落 wx-plugin；
 * 缺件（非微信单）或微信换不到 → 不落 wx-plugin，渠道留空（读路径走自建地图）。
 */
class WxWaybillBindServiceTest {

    private final LogisticsService logistics = mock(LogisticsService.class);
    private final TraceDisplayPort displayPort = mock(TraceDisplayPort.class);
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);
    private final TradeMappers.SubOrderMapper subOrderMapper = mock(TradeMappers.SubOrderMapper.class);
    private final TradeMappers.OrderMapper orderMapper = mock(TradeMappers.OrderMapper.class);
    private final TradeMappers.OrderItemMapper itemMapper = mock(TradeMappers.OrderItemMapper.class);

    private WxWaybillBindService service() {
        return new WxWaybillBindService(logistics, displayPort, ledger,
                subOrderMapper, orderMapper, itemMapper);
    }

    /** 一条待备运单 SH-1 / SUB-1 / SO-1，openid 与交易号由参数给（null = 非微信单） */
    private void wireOneTarget(String openid, String payTradeNo) {
        when(logistics.wxBindTargets(anyInt())).thenReturn(List.of(
                new WxBindTarget("SH-1", "SUB-1", "STO", "7734001")));
        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo("SUB-1");
        sub.setOrderNo("SO-1");
        // 申通/中通等运力换 token 必填（微信 receiver_phone）：缺了回 9300561
        sub.setReceiverPhone("18503088359");
        when(subOrderMapper.selectOne(any())).thenReturn(sub);
        OrdOrder ord = new OrdOrder();
        ord.setOrderNo("SO-1");
        ord.setPayTradeNo(payTradeNo);
        when(orderMapper.selectOne(any())).thenReturn(ord);
        OrdItem item = new OrdItem();
        item.setSubOrderNo("SUB-1");
        item.setTitle("盐 1kg");
        item.setCover("http://x/salt.png");
        when(itemMapper.selectOne(any())).thenReturn(item);
        when(ledger.paidPayment("SO-1")).thenReturn(openid == null
                ? Optional.empty()
                : Optional.of(new PaidPayment("OUT-1", openid, "wxappid")));
    }

    @Test
    void bindsWxPluginWhenTokenObtained() {
        wireOneTarget("oABC123", "4200001234");
        when(displayPort.decide(eq(Surface.MP), any(ShipmentCtx.class)))
                .thenReturn(Optional.of(new DisplayPayload("wx-plugin", "TOKEN-xyz", List.of(), null, true)));

        var r = service().bindPending(50);

        assertThat(r.attempted()).isEqualTo(1);
        assertThat(r.prepared()).isEqualTo(1);
        verify(logistics).applyWxDisplay("SH-1", "wx-plugin", "TOKEN-xyz", null);

        // 组装给微信的上下文确实带了付款人 openid + 微信交易号（否则换不到 token）
        ArgumentCaptor<ShipmentCtx> ctx = ArgumentCaptor.forClass(ShipmentCtx.class);
        verify(displayPort).decide(eq(Surface.MP), ctx.capture());
        assertThat(ctx.getValue().buyerOpenid()).isEqualTo("oABC123");
        assertThat(ctx.getValue().transId()).isEqualTo("4200001234");
        assertThat(ctx.getValue().goodsName()).isEqualTo("盐 1kg");
        // 申通单：收件人手机号必须带上，否则微信回 9300561「收件人手机号错误」
        assertThat(ctx.getValue().receiverPhone()).isEqualTo("18503088359");
    }

    @Test
    void staysSelfMapWhenNoToken() {
        wireOneTarget(null, null);  // 非微信单：支付流水上没有 openid
        when(displayPort.decide(eq(Surface.MP), any(ShipmentCtx.class)))
                .thenReturn(Optional.of(new DisplayPayload("self-map", null, List.of(), null, false)));
        when(displayPort.lastFailReason()).thenReturn(null);

        var r = service().bindPending(50);

        assertThat(r.prepared()).isZero();
        // 渠道/token 留空（读路径落自建），只占位 displayPreparedAt（failReason=null）
        verify(logistics).applyWxDisplay("SH-1", null, null, null);
        verify(logistics, never()).applyWxDisplay(eq("SH-1"), eq("wx-plugin"), any(), any());
    }
}
