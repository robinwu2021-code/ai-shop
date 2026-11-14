package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.trade.service.OrderService;

import java.util.List;

/** Safe persisted command, without bearer identity references or external request keys. */
public record CheckoutCommand(
        OrderService.CreateOrderCommand command, List<CheckoutLineReq> lines) {
    public CheckoutCommand {
        lines = List.copyOf(lines);
    }
}
