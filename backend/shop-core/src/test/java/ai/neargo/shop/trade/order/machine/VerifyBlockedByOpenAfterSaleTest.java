package ai.neargo.shop.trade.order.machine;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.machine.order.OrderMachine;
import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 有进行中售后时，核销与「商家标记送达」都不许把单推到完成。
 *
 * <p>发货、备货、自动确认收货早就要求「没有进行中售后」，唯独这两条收口边漏了：
 * 买家申请了退款、商家还没处理，店员一扫码单就成了 COMPLETED —— 售后期从这一刻起算、
 * 结算解冻开始计时、评价开放，而那张退款单还挂着。
 */
class VerifyBlockedByOpenAfterSaleTest {

    @ParameterizedTest
    @EnumSource(value = OrderEvent.class, names = {"VERIFIED", "DELIVERED_BY_MERCHANT"})
    @DisplayName("★★★ 状态机：有进行中售后 → 核销 / 标记送达被拒（CONFLICT）")
    void machineRejectsWhileAfterSaleOpen(OrderEvent event) {
        OrderContext open = OrderContext.authorizedOnly().withOpenAfterSale(true);
        assertThatThrownBy(() -> OrderMachine.MACHINE.fire(OrderStatus.FULFILLING, event, Actor.MERCHANT, open))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(OrderMachine.MACHINE.fire(OrderStatus.FULFILLING, event, Actor.MERCHANT,
                OrderContext.authorizedOnly()).to()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("★★★ 写入口自己查售后占位：调用方传 authorizedOnly() 也拦得住（开着退款 → 核销被拒）")
    void writerLooksUpOpenAfterSaleItself() {
        StatusCasMapper cas = mock(StatusCasMapper.class);
        OrderStatusLogMapper logs = mock(OrderStatusLogMapper.class);
        when(cas.orderStatus(any())).thenReturn("FULFILLING");
        when(cas.casOrder(any(), any(), any(), any())).thenReturn(1);
        when(cas.markOrderCompleted(any(), anyLong())).thenReturn(1);
        // 买家刚申请了退款：子单上挂着售后占位
        when(cas.openAfterSaleNo("S1")).thenReturn("AS1");
        TradeStatusWriter writer = new TradeStatusWriter(cas, logs);

        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo("S1");
        sub.setStatus("FULFILLING");

        /*
         * 所有核销调用点（自提核销、团长核销、行业核销）传的都是 authorizedOnly() ——
         * 守卫只加在状态机上而不在写入口补事实，等于没加。
         */
        assertThatThrownBy(() -> writer.order(sub, OrderEvent.VERIFIED, Actor.MERCHANT, "STAFF", "已取货",
                OrderContext.authorizedOnly()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(cas, never()).casOrder(any(), any(), any(), any());

        // 只有存量售后行、没有占位的老数据同样要拦
        when(cas.openAfterSaleNo("S1")).thenReturn(null);
        when(cas.countOpenAfterSales("S1")).thenReturn(1L);
        assertThatThrownBy(() -> writer.order(sub, OrderEvent.DELIVERED_BY_MERCHANT, Actor.MERCHANT, "M1",
                "商家标记送达", OrderContext.authorizedOnly()))
                .isInstanceOf(BizException.class);

        // 售后收口之后照常放行
        when(cas.countOpenAfterSales("S1")).thenReturn(0L);
        assertThat(writer.order(sub, OrderEvent.VERIFIED, Actor.MERCHANT, "STAFF", "已取货",
                OrderContext.authorizedOnly()).to()).isEqualTo(OrderStatus.COMPLETED);
    }
}
