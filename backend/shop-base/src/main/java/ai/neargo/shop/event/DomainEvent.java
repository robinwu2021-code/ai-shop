package ai.neargo.shop.event;

/**
 * 领域事件。实现类放 {@code shop-spi}，因为发布方与消费方分属不同模块，
 * 事件类型是它们之间的契约 —— 契约不能住在任一方的实现里。
 */
public interface DomainEvent {

    /** 聚合类型，如 {@code ORDER} / {@code SUB_ORDER} / {@code MERCHANT}。 */
    String aggregateType();

    /** 聚合业务键，如 {@code SO20260805...}。 */
    String aggregateId();

    /** 事件类型，如 {@code ORDER_PAID}。用大写下划线，与前端枚举口径一致。 */
    String eventType();
}
