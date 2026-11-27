package ai.neargo.shop.event;

/**
 * 通知类事件提交后怎么送出去（ADR-067）。没有这个 bean 时 {@link AfterCommitNotifications} 走进程内线程池；
 * {@code shop.events.transport=rabbitmq} 时由 RabbitMQ 实现接管，消费端在各自队列里收。
 */
public interface NotificationTransport {

    /** 业务事务已提交后调用；不得抛异常拖累调用方。 */
    void send(SysOutbox envelope);
}
