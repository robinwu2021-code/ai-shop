package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.machine.payment.PaymentContext;
import ai.neargo.shop.trade.order.machine.payment.PaymentEvent;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.service.OrderStateMachine;

/** Legacy target-state API delegates every production write to the shared CAS/log writer. */
public class OrderTransitions {
    private final TradeStatusWriter writer;

    public OrderTransitions(TradeStatusWriter writer) {
        this.writer = writer;
    }

    /** Translate legacy payment targets while retaining the original transition validation. */
    public void applyOrder(OrdOrder order, String to, Operator operator, String reason) {
        OrderStateMachine.assertOrderTransit(order.getStatus(), to);
        if (to.equals(order.getStatus())) {
            return;
        }
        boolean offline = OrdOrder.WAIT_OFFLINE_PAY.equals(order.getStatus());
        PaymentEvent event = switch (to) {
            case OrdOrder.PAID -> offline ? PaymentEvent.OFFLINE_CONFIRMED : PaymentEvent.PAY_SUCCEEDED;
            case OrdOrder.CANCELLED -> PaymentEvent.CANCEL;
            case OrdOrder.CLOSED -> PaymentEvent.PAY_TIMEOUT;
            default -> throw new IllegalArgumentException("Unsupported payment target: " + to);
        };
        Actor actor = switch (event) {
            case PAY_SUCCEEDED -> Actor.CHANNEL;
            case OFFLINE_CONFIRMED -> Actor.MERCHANT;
            case PAY_TIMEOUT -> Actor.SYSTEM;
            default -> Actor.BUYER;
        };
        writer.payment(order, event, actor, number(operator), reason, new PaymentContext(offline, true, true, true));
    }

    /** Preserve the legacy caller API while the event/CAS gate remains authoritative. */
    public void applySub(OrdSubOrder order, String to, Operator operator, String reason) {
        OrderStateMachine.assertSubOrderTransit(order.getStatus(), to);
        transit(order, to, operator, reason);
    }

    /** Merchant eligibility retains its stricter unpaid guard before shared state/event translation. */
    public void applyMerchantSub(OrdSubOrder order, String to, Operator operator, String reason) {
        OrderStateMachine.assertMerchantSubOrderTransit(order.getStatus(), to);
        transit(order, to, operator, reason);
    }

    private void transit(OrdSubOrder order, String to, Operator operator, String reason) {
        if (to.equals(order.getStatus())) {
            return;
        }
        Actor actor = actor(operator);
        OrderEvent event = event(order, to, actor);
        if (event == OrderEvent.PAY_CONFIRMED || event == OrderEvent.SHIPPED
                || event == OrderEvent.CANCEL || event == OrderEvent.FULLY_REFUNDED) {
            actor = Actor.SYSTEM;
        }
        OrderContext context = OrderContext.authorizedOnly()
                .withServiceClass(OrdSubOrder.FULFILLING.equals(to) && OrdSubOrder.WAIT_PAY.equals(order.getStatus()))
                .withFullyRefunded(OrdSubOrder.REFUNDED.equals(to))
                .withAutoReceiptDue(event == OrderEvent.RECEIPT_AUTO_CONFIRMED);
        writer.order(order, event, actor, number(operator), reason, context);
    }

    private static OrderEvent event(OrdSubOrder order, String to, Actor actor) {
        return switch (to) {
            case OrdSubOrder.WAIT_FULFILL -> OrderEvent.PAY_CONFIRMED;
            case OrdSubOrder.FULFILLING -> OrdSubOrder.WAIT_PAY.equals(order.getStatus()) ? OrderEvent.PAY_CONFIRMED
                    : actor == Actor.MERCHANT ? OrderEvent.SHIP_BY_MERCHANT : OrderEvent.SHIPPED;
            case OrdSubOrder.COMPLETED -> actor == Actor.MERCHANT ? OrderEvent.DELIVERED_BY_MERCHANT
                    : actor == Actor.SYSTEM ? OrderEvent.RECEIPT_AUTO_CONFIRMED : OrderEvent.RECEIPT_CONFIRMED;
            case OrdSubOrder.CANCELLED -> OrderEvent.CANCEL;
            case OrdSubOrder.REFUNDED -> OrderEvent.FULLY_REFUNDED;
            default -> throw new IllegalArgumentException("Unsupported order target: " + to);
        };
    }

    private static Actor actor(Operator operator) {
        if (operator == null) {
            return Actor.SYSTEM;
        }
        return switch (operator.type()) {
            case OrdStatusLog.BY_MERCHANT -> Actor.MERCHANT;
            case OrdStatusLog.BY_USER -> Actor.BUYER;
            case OrdStatusLog.BY_PLATFORM -> Actor.PLATFORM;
            default -> Actor.SYSTEM;
        };
    }

    private static String number(Operator operator) {
        return operator == null ? null : operator.no();
    }
}
