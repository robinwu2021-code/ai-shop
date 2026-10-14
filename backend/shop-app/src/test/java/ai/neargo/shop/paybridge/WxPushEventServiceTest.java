package ai.neargo.shop.paybridge;

import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信消息推送事件（批 B）。
 *
 * <p>守两件事：**只认结算事件**（别的事件一律不动状态），
 * 以及**解析不依赖嵌套层级**（微信文档只给了字段表没给报文结构，
 * 硬按猜的层级取值时「取不到」和「没接」长得一模一样）。
 */
class WxPushEventServiceTest {

    private final OrderService orderService = mock(OrderService.class);
    private final TradeMappers.SubOrderMapper subOrderMapper = mock(TradeMappers.SubOrderMapper.class);
    private final TradeMappers.OrderMapper orderMapper = mock(TradeMappers.OrderMapper.class);

    private WxPushEventService service() {
        return new WxPushEventService(orderService, subOrderMapper, orderMapper, new ObjectMapper());
    }

    private void oneFulfillingSubOrder() {
        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo("SUB-1");
        sub.setOrderNo("SO-1");
        sub.setStatus(OrdSubOrder.FULFILLING);
        when(subOrderMapper.selectList(any())).thenReturn(List.of(sub));
    }

    @Test
    @DisplayName("★★★ 结算事件（商户单号在顶层）→ 推进子单到已完成")
    void settlementAtTopLevel() {
        oneFulfillingSubOrder();
        String body = """
                {"event":"trade_manage_order_settlement","merchant_trade_no":"SO-1",
                 "confirm_receive_method":1,"confirm_receive_time":1791500000}""";

        assertThat(service().onEvent(body)).isEqualTo(1);
        verify(orderService).confirmReceipt("SUB-1");
    }

    @Test
    @DisplayName("★★★ 同样的字段**埋在嵌套里**也要认出来 —— 微信没给报文结构，不能赌层级")
    void settlementNested() {
        oneFulfillingSubOrder();
        String body = """
                {"ToUserName":"gh_x","Event":"trade_manage_order_settlement",
                 "order":{"order_key":{"merchant_trade_no":"SO-1"},
                 "settlement":{"confirm_receive_method":2,"settlement_time":1791500000}}}""";

        assertThat(service().onEvent(body)).isEqualTo(1);
        verify(orderService).confirmReceipt("SUB-1");
    }

    @Test
    @DisplayName("★★★ 别的事件一律不动状态")
    void otherEventDoesNothing() {
        // **必须先摆一张可完成的子单**：不摆的话去掉事件类型判断也查不到东西、照样返回 0，
        // 这条用例就成了假绿（2026-10-09 消融当场抓到）
        oneFulfillingSubOrder();
        String body = """
                {"Event":"trade_manage_remind_shipping","merchant_trade_no":"SO-1"}""";

        assertThat(service().onEvent(body)).isZero();
        verify(orderService, never()).confirmReceipt(any());
    }

    @Test
    @DisplayName("★★ 只带微信交易号时反查订单号")
    void resolvesByTransactionId() {
        OrdOrder order = new OrdOrder();
        order.setOrderNo("SO-1");
        when(orderMapper.selectOne(any())).thenReturn(order);
        oneFulfillingSubOrder();
        String body = """
                {"Event":"trade_manage_order_settlement","transaction_id":"4200001234"}""";

        assertThat(service().onEvent(body)).isEqualTo(1);
        verify(orderService).confirmReceipt("SUB-1");
    }

    @Test
    @DisplayName("★★★ 认不出订单标识：不动状态、不抛 —— 原文落日志，等第一条真事件来定结构")
    void unknownOrderIsLoggedNotThrown() {
        String body = """
                {"Event":"trade_manage_order_settlement","some_unknown_key":"zzz"}""";

        assertThat(service().onEvent(body)).isZero();
        verify(orderService, never()).confirmReceipt(any());
    }

    @Test
    @DisplayName("★★ 幂等：已经完成的子单不在候选里（兜底定时器可能先一步完成过）")
    void alreadyCompletedIsNotTouched() {
        when(subOrderMapper.selectList(any())).thenReturn(List.of());   // 查的就是 FULFILLING
        String body = """
                {"Event":"trade_manage_order_settlement","merchant_trade_no":"SO-1"}""";

        assertThat(service().onEvent(body)).isZero();
        verify(orderService, never()).confirmReceipt(any());
    }

    @Test
    @DisplayName("★★ 不是 JSON（XML 明文模式）不抛，只记日志")
    void nonJsonIsTolerated() {
        assertThat(service().onEvent("<xml><Event>x</Event></xml>")).isZero();
        verify(orderService, never()).confirmReceipt(any());
    }

    @Test
    @DisplayName("★★ 单张子单被业务规则拒（售后进行中）不影响同单其余")
    void oneRejectedDoesNotBlockOthers() {
        OrdSubOrder a = new OrdSubOrder();
        a.setSubOrderNo("SUB-A");
        a.setOrderNo("SO-1");
        OrdSubOrder b = new OrdSubOrder();
        b.setSubOrderNo("SUB-B");
        b.setOrderNo("SO-1");
        when(subOrderMapper.selectList(any())).thenReturn(List.of(a, b));
        when(orderService.confirmReceipt(eq("SUB-A"))).thenThrow(new IllegalStateException("售后进行中"));

        assertThat(service().onEvent("""
                {"Event":"trade_manage_order_settlement","merchant_trade_no":"SO-1"}""")).isEqualTo(1);
        verify(orderService).confirmReceipt("SUB-B");
    }
}
