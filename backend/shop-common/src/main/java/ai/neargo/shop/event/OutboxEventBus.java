package ai.neargo.shop.event;

import ai.neargo.shop.common.BizKey;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 事件发布：<b>只写库，不发 MQ</b>。
 *
 * <p>调用方必须处在业务事务里 —— 这正是 Outbox 的全部意义：
 * 事件与业务数据同生共死。投递交给 {@code OutboxRelay}（S2 随 MQ 一起接）。
 *
 * <p>刻意不提供「同步发送」的重载。留一个后门，就一定会有人在赶工期时用它，
 * 然后我们又回到「订单成了但事件丢了」的世界。
 */
@Component
public class OutboxEventBus {

    public static final String PENDING = "PENDING";

    private final SysOutboxMapper mapper;
    private final ObjectMapper json;

    public OutboxEventBus(SysOutboxMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public void publish(DomainEvent event) {
        SysOutbox row = new SysOutbox();
        row.setEventNo(BizKey.next(BizKey.EVENT));
        row.setAggregateType(event.aggregateType());
        row.setAggregateId(event.aggregateId());
        row.setEventType(event.eventType());
        row.setPayload(toJson(event));
        row.setStatus(PENDING);
        row.setRetryCount(0);
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
    }

    private String toJson(DomainEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化失败必须炸掉整个业务事务：一个发不出去的事件，比业务失败更难排查
            throw new IllegalStateException("event serialize failed: " + event.eventType(), e);
        }
    }
}
