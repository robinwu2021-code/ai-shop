package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 签收 → 确认收货提醒：一个支付单的快递全签收了才提醒（微信每单只给一次机会）。 */
class WxConfirmOnSignedConsumerTest {

    private final WxConfirmReceiveService confirm = mock(WxConfirmReceiveService.class);
    private final LogisticsPort logistics = mock(LogisticsPort.class);
    private final TradeMappers.SubOrderMapper subOrders = mock(TradeMappers.SubOrderMapper.class);

    private WxConfirmOnSignedConsumer consumer() {
        return new WxConfirmOnSignedConsumer(confirm, logistics, subOrders, new ObjectMapper());
    }

    private static OrdSubOrder sub(String no, String status) {
        OrdSubOrder s = new OrdSubOrder();
        s.setSubOrderNo(no);
        s.setOrderNo("SO1");
        s.setStatus(status);
        s.setFulfillment(OrdSubOrder.EXPRESS);
        return s;
    }

    private static SysOutbox signed(String profile) {
        SysOutbox e = new SysOutbox();
        e.setPayload("{\"shipmentNo\":\"SH1\",\"bizRef\":\"A\",\"profile\":\"" + profile + "\",\"signedAt\":100}");
        return e;
    }

    @Test
    @DisplayName("★★★ 两个包裹只签收了一个 → 不提醒（买家还在等第二个）")
    void waitsForAllPackages() {
        when(subOrders.selectOne(any())).thenReturn(sub("A", OrdSubOrder.FULFILLING));
        when(subOrders.selectList(any())).thenReturn(List.of(sub("A", OrdSubOrder.FULFILLING), sub("B", OrdSubOrder.FULFILLING)));
        when(logistics.signedAtOf(any())).thenReturn(Map.of("A", 100L));
        consumer().consume(signed("WX"));
        verify(confirm, never()).notifyOne(any(), anyLong());
    }

    @Test
    @DisplayName("★★★ 全签收了 → 提醒一次，签收时间取最晚那张")
    void notifiesWithLatest() {
        when(subOrders.selectOne(any())).thenReturn(sub("B", OrdSubOrder.FULFILLING));
        when(subOrders.selectList(any())).thenReturn(List.of(sub("A", OrdSubOrder.FULFILLING), sub("B", OrdSubOrder.FULFILLING)));
        when(logistics.signedAtOf(any())).thenReturn(Map.of("A", 100L, "B", 300L));
        consumer().consume(signed("WX"));
        verify(confirm).notifyOne(eq("SO1"), eq(300L));
    }

    @Test
    @DisplayName("★★ 退款了的子单不挡；线下付款单不提醒（微信没有这笔交易）")
    void refundedDoesNotBlockAndSelfSkipped() {
        when(subOrders.selectOne(any())).thenReturn(sub("A", OrdSubOrder.FULFILLING));
        when(subOrders.selectList(any())).thenReturn(List.of(sub("A", OrdSubOrder.FULFILLING), sub("B", OrdSubOrder.REFUNDED)));
        when(logistics.signedAtOf(any())).thenReturn(Map.of("A", 100L));
        consumer().consume(signed("WX"));
        verify(confirm).notifyOne(eq("SO1"), eq(100L));

        WxConfirmOnSignedConsumer c2 = new WxConfirmOnSignedConsumer(mock(WxConfirmReceiveService.class), logistics,
                subOrders, new ObjectMapper());
        c2.consume(signed("SELF"));
        verify(subOrders, org.mockito.Mockito.times(1)).selectOne(any());
    }
}
