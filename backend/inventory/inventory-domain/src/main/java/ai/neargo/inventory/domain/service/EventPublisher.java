package ai.neargo.inventory.domain.service;

/**
 * 写一条给宿主拉取的事件（TDD §2.12、§2.13）。与业务写入同事务；序号在提交后才分配。
 *
 * <p>信封：事件号、类型、业主、资源类型 / 号 / 版本、发生时间、操作人、链路号、载荷。
 * 宿主按资源版本丢弃旧事件 —— 重放与乱序都不会把新状态改回旧状态。
 */
public interface EventPublisher {

    void publish(Caller caller, String eventType, String resourceType, String resourceId, int resourceVersion,
                 Object payload);
}
