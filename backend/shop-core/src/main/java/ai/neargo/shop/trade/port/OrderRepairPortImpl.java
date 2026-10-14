package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.OrderRepairPort;
import ai.neargo.shop.trade.service.OrderService;
import org.springframework.stereotype.Component;

/**
 * {@link OrderRepairPort} 实现：转发到既有的支付成功与关单链路。
 *
 * <p><b>刻意只做转发。</b> 这一层不加任何判断 —— 加了的话，
 * 「对账补的单」与「回调补的单」就会走出两条略有差异的路径，
 * 而差异会出现在积分、券、结算单里的某一处，且不报错。
 */
@Component
public class OrderRepairPortImpl implements OrderRepairPort {

    private final OrderService orderService;

    public OrderRepairPortImpl(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void markPaid(String orderNo, String payChannel, String tradeNo) {
        orderService.markPaid(orderNo, payChannel, tradeNo);
    }

    @Override
    public void closeUnpaid(String orderNo) {
        orderService.closeUnpaid(orderNo, "对账自查：通道确认无此单");
    }
}
