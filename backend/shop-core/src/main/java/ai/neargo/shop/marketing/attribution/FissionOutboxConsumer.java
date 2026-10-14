package ai.neargo.shop.marketing.attribution;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 首单回填：`ORDER_CREATED` → 裂变台账的 {@code order_no}（= 转化）。
 *
 * <p><b>为什么走事件而不是在下单方法里插一行</b>：与 {@code RiskOutboxConsumer}
 * 同一条理由 —— `OrderEvents.OrderCreated` 的类注释里本来就写着
 * 「消费方：<b>marketing</b> · message · risk」，而 marketing 这条一直是空的。
 * 走事件，**交易域一行代码都不用改**，也不会让一次营销统计失败去回滚一笔订单。
 *
 * <p><b>这条链路此前完全不存在</b>：`mkt_fission_invite.order_no` 与
 * `mkt_attribution_log.order_no` 两列的注释都写着「由 ORDER_CREATED 事件回填」，
 * 而在 2026-08-30 之前没有任何代码写过它们 —— 于是「邀请转化了几个」
 * 在数据里永远是空的，运营端「邀请有礼」那两列因此恒为 0。
 *
 * <p><b>幂等</b>：Outbox 是 at-least-once，而 {@code onFirstOrder} 只回填
 * 「还没有首单」的行 —— 同一张订单被投两次，第二次一行都不会动。
 */
@Component
public class FissionOutboxConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(FissionOutboxConsumer.class);
    private static final Set<String> HANDLED = Set.of("ORDER_CREATED");

    private final FissionInviteService inviteService;
    private final ObjectMapper json;

    public FissionOutboxConsumer(FissionInviteService inviteService, ObjectMapper json) {
        this.inviteService = inviteService;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode payload = json.readTree(event.getPayload());
        JsonNode userNo = payload.get("userNo");
        JsonNode orderNo = payload.get("orderNo");
        if (userNo == null || userNo.isNull() || orderNo == null || orderNo.isNull()) {
            return;
        }
        try {
            inviteService.onFirstOrder(userNo.asString(), orderNo.asString());
        } catch (RuntimeException e) {
            // 统计口径失败不该让 outbox 反复重投一条永远处理不了的事件
            log.warn("[裂变] 首单回填失败 user={} order={}：{}",
                    userNo.asString(), orderNo.asString(), e.toString());
        }
    }
}
