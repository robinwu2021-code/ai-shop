package ai.neargo.shop.risk;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.risk.impl.RiskDetector;
import ai.neargo.shop.spi.risk.RiskEventPort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 交易与售后 → 风控识别（P-16.2.1 刷单 / P-16.2.3 恶意退款）。
 *
 * <p><b>为什么走事件而不是扫表</b>：扫表要跨域直读 {@code ord_order} 与
 * {@code ord_after_sale}，ArchUnit 第 1 条当场就会拦下来 —— 而那条规则拦的正是
 * 「为了几个数捅穿一层边界」：捅了之后交易域改一个列名，风控就跟着炸，
 * 而炸的时候没人会想到是风控。
 *
 * <p>{@code OrderEvents.OrderCreated} 的类注释里本来就写着
 * 「消费方：marketing · message · <b>risk(行为画像)</b>」——
 * 这条消费方一直是空的，本类把它补上。**交易域一行代码都不用改。**
 *
 * <p>幂等由 {@link RiskDetector} 的 {@code (type, evidenceRef)} 唯一索引兜底：
 * Outbox 是 at-least-once，同一张订单被投两次时只计一次命中。
 * 没有它，投递器重启一次就能把一个正常用户送进黑名单。
 */
@Component
public class RiskOutboxConsumer implements OutboxConsumer {

    private static final Set<String> HANDLED = Set.of("ORDER_CREATED", "AFTER_SALE_REFUNDED");

    private final RiskDetector detector;
    private final ObjectMapper json;

    public RiskOutboxConsumer(RiskDetector detector, ObjectMapper json) {
        this.detector = detector;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode payload = json.readTree(event.getPayload());
        String userNo = text(payload, "userNo");
        if (userNo == null) {
            return;
        }
        switch (event.getEventType()) {
            /*
             * 刷单：主体是**下单的人**，不是商家。
             *
             * 载荷里没有 merchantNo（OrderCreated 只给主单），所以这一版按人计数
             * 而不是按「人×店」—— 那需要交易域多发一个字段，而改交易域正是这条链路
             * 要避免的事。按人计数漏掉「专门刷一家店」的形态，写在 TDD §四 里，
             * 等真有样本时再决定值不值得让交易域加字段。
             */
            case "ORDER_CREATED" -> detector.hit(RiskEventPort.FAKE_ORDER,
                    RiskEventPort.SUBJECT_USER, userNo, null,
                    event.getAggregateId(), "下单 " + event.getAggregateId());
            /*
             * 恶意退款：**只认退款成功**，不认「申请了售后」。
             * 按申请数计的话，一个合理维权的买家（收到坏了的鸡蛋，申请，商家秒退）
             * 与一个职业退款人长得一模一样 —— 区别在于钱有没有真的退出去。
             */
            case "AFTER_SALE_REFUNDED" -> detector.hit(RiskEventPort.MALICIOUS_REFUND,
                    RiskEventPort.SUBJECT_USER, userNo, null,
                    event.getAggregateId(), "退款 " + event.getAggregateId()
                            + "（" + payload.get("refundMinor") + " 分）");
            default -> {
                // supports() 已经筛过，走到这里说明两处清单分叉了
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
