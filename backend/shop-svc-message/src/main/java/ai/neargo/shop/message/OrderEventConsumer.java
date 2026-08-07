package ai.neargo.shop.message;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 交易事件 → 站内消息（C-14.1）。**Outbox 的第一个消费者**。
 *
 * <p>只发用户**必须知道**的三件事：钱扣了、货到了、钱退了。
 * 每多一条通知，用户对通知的信任就少一点 —— 交易消息被活动消息淹没之后，
 * 「到货了去取」这条最重要的通知也会被划走。
 *
 * <p>幂等靠 {@code dedupKey = eventNo}：投递是 at-least-once，
 * 同一事件重投时第二条会被唯一索引挡下。
 */
@Component
public class OrderEventConsumer implements OutboxConsumer {

    private static final Set<String> HANDLED = Set.of(
            "ORDER_PAID", "SUB_ORDER_COMPLETED", "AFTER_SALE_REFUNDED");

    private final MessageService messageService;
    private final ObjectMapper json;

    public OrderEventConsumer(MessageService messageService, ObjectMapper json) {
        this.messageService = messageService;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode payload = json.readTree(event.getPayload());
        switch (event.getEventType()) {
            case "ORDER_PAID" -> messageService.push(
                    text(payload, "userNo"), MessageService.TRADE,
                    "支付成功", "订单已支付，商家备货后可凭取货码到店自提",
                    "/pages/order/index?orderNo=" + event.getAggregateId(),
                    event.getEventNo());
            case "SUB_ORDER_COMPLETED" -> messageService.push(
                    text(payload, "userNo"), MessageService.TRADE,
                    "已取货", "订单已完成，欢迎评价",
                    "/pages/order/index?orderNo=" + event.getAggregateId(),
                    event.getEventNo());
            case "AFTER_SALE_REFUNDED" -> messageService.push(
                    text(payload, "userNo"), MessageService.TRADE,
                    "退款已处理", "退款将原路退回，到账时间以支付渠道为准",
                    "/pages/after-sale/index?afterSaleNo=" + event.getAggregateId(),
                    event.getEventNo());
            default -> {
                // supports() 已经过滤，走到这里说明两处不一致 —— 什么都不做比乱发消息强
            }
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node == null ? null : node.asString();
    }
}
