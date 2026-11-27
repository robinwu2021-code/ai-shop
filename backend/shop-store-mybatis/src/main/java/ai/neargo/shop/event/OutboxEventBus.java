package ai.neargo.shop.event;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerProperties;
import ai.neargo.shop.event.broker.MessageBroker;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import org.springframework.beans.factory.annotation.Autowired;
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

    private UnifiedOutboxFacade unified;

    @Autowired(required = false)
    public void setUnified(UnifiedOutboxFacade unified) { this.unified = unified; }

    private AfterCommitNotifications notifications;

    @Autowired(required = false)
    public void setNotifications(AfterCommitNotifications notifications) { this.notifications = notifications; }

    /** 用时才取，理由同 {@link #pumpProvider}：消费者里有依赖本类的。 */
    private ObjectProvider<OutboxConsumer> consumers;

    @Autowired(required = false)
    public void setConsumers(ObjectProvider<OutboxConsumer> consumers) { this.consumers = consumers; }

    /** 配了消息中间件时，{@link #forward} 对名单里的类型直接发中间件。 */
    private MessageBroker broker;
    private BrokerProperties brokerProperties;

    @Autowired(required = false)
    public void setBroker(MessageBroker broker) { this.broker = broker; }

    @Autowired(required = false)
    public void setBrokerProperties(BrokerProperties brokerProperties) { this.brokerProperties = brokerProperties; }

    /** 每个事件类型的走法；消费者集合启动后不变，算一次缓存。 */
    private final java.util.Map<String, Route> routes = new java.util.concurrent.ConcurrentHashMap<>();

    /** @param afterCommit 有通知类消费者 @param durable 有必达（发件箱）消费者 */
    private record Route(boolean afterCommit, boolean durable) { }

    /**
     * 有通知类消费者就登记提交后通知；返回还要不要落发件箱。
     * 只有通知类消费者时不落；没有任何消费者时照旧落（存档语义不变）。
     */
    private boolean persistAfterNotifying(String eventNo, DomainEvent event) {
        if (notifications == null || consumers == null) {
            return true;
        }
        Route route = route(event.eventType());
        if (route.afterCommit()) {
            notifications.publish(eventNo, event);
        }
        return route.durable() || !route.afterCommit();
    }

    private Route route(String eventType) {
        return routes.computeIfAbsent(eventType, t -> {
            boolean afterCommit = false, durable = false;
            for (OutboxConsumer c : consumers.orderedStream().toList()) {
                if (!c.supports(t)) continue;
                if (c.afterCommitOnly()) afterCommit = true; else durable = true;
            }
            return new Route(afterCommit, durable);
        });
    }

    /** Only obsolete failed targets are skipped; successful and in-flight targets are preserved. */
    public void supersedeFailed(String aggregateType, String aggregateId, java.util.Set<String> handlers,
                                String reason) {
        if (unified != null && unified.enabled("PLATFORM")) {
            String actor = ai.neargo.shop.auth.SecurityUtils.currentUserNoOrNull();
            unified.supersedeFailed("PLATFORM", aggregateType, aggregateId, handlers,
                    actor == null ? "SYSTEM:LOGISTICS" : actor, reason);
        }
    }

    /** Publish an externally-owned immutable event with its stable source key. */
    public void publish(String eventNo, DomainEvent event) {
        if (eventNo == null || eventNo.isBlank() || event == null) throw new IllegalArgumentException("event identity required");
        if (unified != null && unified.isMaintenance()) throw new IllegalStateException("Outbox maintenance");
        if (!persistAfterNotifying(eventNo, event)) {
            return;
        }
        String payload = toJson(event);
        SysOutbox row = new SysOutbox();
        row.setEventNo(eventNo);
        row.setAggregateType(event.aggregateType());
        row.setAggregateId(event.aggregateId());
        row.setEventType(event.eventType());
        row.setPayload(payload);
        row.setStatus(PENDING);
        row.setRetryCount(0);
        row.setCreatedAt(LocalDateTime.now());
        int inserted = mapper.insertIfAbsent(row);
        if (inserted == 1) {
            pumpAfterCommit(row);
            return;
        }
        SysOutbox existing = mapper.findByEventNo(eventNo);
        if (existing == null || !java.util.Objects.equals(existing.getAggregateType(), event.aggregateType())
                || !java.util.Objects.equals(existing.getAggregateId(), event.aggregateId())
                || !java.util.Objects.equals(existing.getEventType(), event.eventType())
                || !java.util.Objects.equals(existing.getPayload(), payload)) {
            throw new IllegalStateException("event number replay has different immutable facts: " + eventNo);
        }
    }

    /**
     * 转发一条<b>源头已在自己库里持久化</b>的事件（源头有自己的发件箱、发不出会按原事件号重发），如 PAY 权益事件。
     *
     * <p>类型在中间件名单里：直接发中间件，确认即返回，不再落本库发件箱 —— 落了就是两个发件箱接力（清单 §〇 C 类“撤中转”）。
     * 不在名单里（含 {@code transport=local}）：同 {@link #publish(String, DomainEvent)}，进程内投递仍要靠本库发件箱。
     * 两条路都保留原事件号，下游按它幂等。中间件没确认就抛出，源头照它的退避重发。
     */
    public void forward(String eventNo, DomainEvent event) {
        if (broker != null && brokerProperties != null && event != null && brokerProperties.relays(event.eventType())) {
            if (eventNo == null || eventNo.isBlank()) throw new IllegalArgumentException("event identity required");
            broker.publish(new BrokerMessage(eventNo, event.aggregateType(), event.aggregateId(), event.eventType(),
                    toJson(event)));
            return;
        }
        publish(eventNo, event);
    }

    /**
     * 唯一的发布入口（ADR-067）。按订阅者决定走法：有通知类消费者 → 提交后交给它们；
     * 有必达消费者 → 落发件箱；只有通知类 → 不落发件箱；一个消费者都没有 → 照旧落库（存档语义不变）。
     */
    public void publish(DomainEvent event) {
        if (unified != null && unified.isMaintenance()) { throw new IllegalStateException("Outbox maintenance"); }
        if (!persistAfterNotifying(null, event)) {
            return;
        }
        if (unified != null && unified.enabled("PLATFORM")) {
            unified.publish("PLATFORM", event);
            return;
        }
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
