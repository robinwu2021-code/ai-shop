package ai.neargo.shop.event.outbox.runtime;

import ai.neargo.shop.event.outbox.OutboxEnvelope;

/**
 * 统一投递模式里“哪些投递改发中间件、怎么发”（ADR-067）。由订阅编排在中间件就绪时装上；没装就全部本地执行。
 */
public interface BrokerRelay {

    boolean relays(String eventType);

    /** 中间件确认接收才返回；否则抛异常，运行器按可重试处理。 */
    void publish(OutboxEnvelope envelope, String handlerId);
}
