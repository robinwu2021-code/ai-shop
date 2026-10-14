package ai.neargo.shop.event;

import ai.neargo.shop.common.BizKey;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * 投递泵。**只能用时才取，不能在装配期取**。
     *
     * <p>环是这样的：泵 → {@code OutboxDispatcher} → 所有 {@code OutboxConsumer}
     * → 其中有依赖本类的。{@link ObjectProvider} 本身能打断它，
     * 但第一版在 {@code @PostConstruct} 里就 {@code getIfAvailable()} 了 ——
     * 那还在装配阶段，等于没打断：2026-10-10 全量 2323 个 context 起不来，
     * 报的是 {@code invManagedAppService} 的循环依赖，与 outbox 看着毫无关系。
     *
     * <p>每次 publish 查一次 bean 的开销是一次 map lookup，可以忽略。
     */
    private final ObjectProvider<OutboxPump> pumpProvider;

    public OutboxEventBus(SysOutboxMapper mapper, ObjectMapper json,
                          ObjectProvider<OutboxPump> pumpProvider) {
        this.mapper = mapper;
        this.json = json;
        this.pumpProvider = pumpProvider;
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
        pumpAfterCommit(row);
    }

    /**
     * 事务**提交之后**把这条交给投递泵，让它立刻发（用户 2026-10-10：
     * 「订单完成后直接执行」）。此前要等 5 秒一轮的定时任务。
     *
     * <p><b>必须是 afterCommit，不能是现在就投</b>：现在投的话，
     * 投递线程可能先于本事务提交去读库，查不到那一行 —— 事件静默丢掉，
     * 而且只在高并发下偶尔发生。
     *
     * <p><b>投递泵不在就什么都不做</b>：{@code sys_outbox} 那一行已经落库了，
     * 它才是队列。没有泵的部署（或泵被关掉）靠启动补扫与兜底轮捞 ——
     * 这正是「写库」与「投递」分开的价值，少一边不会丢事件。
     *
     * <p>注册失败（没有活动事务）时**直接投**：没有事务就没有「提交后」，
     * 这种调用方（测试、后台任务）本来就是立即可见的。
     */
    private void pumpAfterCommit(SysOutbox row) {
        OutboxPump pump = pumpProvider.getIfAvailable();
        if (pump == null) {
            return;
        }
        long id = row.getId();
        String aggregateId = row.getAggregateId();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            pump.submit(id, aggregateId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        pump.submit(id, aggregateId);
                    }
                });
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
