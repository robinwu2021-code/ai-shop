package ai.neargo.shop.event.broker;

import ai.neargo.shop.event.DeliveryContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.runtime.BrokerRelay;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.spi.pay.CallSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 把现有消费者登记成中间件订阅（ADR-067）。与具体中间件无关；没有 {@link MessageBroker} 时什么都不做。
 *
 * <ul>
 *   <li>通知类消费者（{@code afterCommitOnly}）：按它声明的 {@code eventTypes()} 订阅，不保序；</li>
 *   <li>必达消费者：只订阅“发件箱改由中间件投递”名单里它支持的类型，保序（同一聚合逐条）。</li>
 * </ul>
 * 收到消息后带着事件号调现有 {@code consume}，消费者代码不改。
 *
 * <p>放在 {@link SmartLifecycle} 里取消费者：消费者有依赖发布总线的，装配期取会成环。
 * 相位比中间件实现早一档，保证实现启动时订阅已登记齐。
 */
public class BrokerSubscriptions implements SmartLifecycle {

    /** 中间件实现用 {@code PHASE + 1}，在订阅登记之后启动。 */
    public static final int PHASE = 0;

    private static final Logger log = LoggerFactory.getLogger(BrokerSubscriptions.class);

    private final ObjectProvider<MessageBroker> brokers;
    private final ObjectProvider<OutboxConsumer> consumers;
    private final BrokerProperties props;
    private final Environment env;
    private static final String PLATFORM = "PLATFORM";
    private volatile boolean running;
    private ObjectProvider<UnifiedOutboxFacade> unifiedFacades;

    /** 统一投递模式的门面；旧模式下不用。 */
    public void setUnifiedFacade(ObjectProvider<UnifiedOutboxFacade> facades) { this.unifiedFacades = facades; }
    private TransactionTemplate perMessage;

    /**
     * 必达订阅每条消息在一个独立事务里调消费者（{@code REQUIRES_NEW}），与进程内投递
     * {@code OutboxDispatcher} 的“每条事件一个事务”一致：消费者没自己声明事务时，多次写库同提交、同回滚。
     * 声明了 {@code NOT_SUPPORTED} 的外部流程消费者照样挂起它。
     */
    public void setTransactionManager(PlatformTransactionManager manager) {
        if (manager == null) {
            perMessage = null;
            return;
        }
        perMessage = new TransactionTemplate(manager);
        perMessage.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public BrokerSubscriptions(ObjectProvider<MessageBroker> brokers, ObjectProvider<OutboxConsumer> consumers,
                               BrokerProperties props, Environment env) {
        this.brokers = brokers;
        this.consumers = consumers;
        this.props = props;
        this.env = env;
    }

    @Override
    public void start() {
        running = true;
        MessageBroker broker = brokers.getIfAvailable();
        if (broker == null) {
            return;
        }
        boolean unified = "unified".equalsIgnoreCase(env.getProperty("shop.outbox.sources.platform.mode", "legacy"));
        int count = 0;
        Map<String, List<OutboxConsumer>> groups = new LinkedHashMap<>();
        Map<String, Set<String>> groupTypes = new LinkedHashMap<>();
        for (OutboxConsumer c : consumers.orderedStream().toList()) {
            if (c.afterCommitOnly()) {
                if (c.eventTypes().isEmpty()) {
                    throw new IllegalStateException("通知类消费者 " + c.handlerId() + " 必须声明 eventTypes()，中间件按它绑定");
                }
                broker.subscribe(new BrokerSubscription(c.handlerId(), c.eventTypes(), false),
                        handler(List.of(c), false));
                count++;
                continue;
            }
            if (unified) {
                continue;   // 统一模式下必达消费者已包成门面里的处理者，按处理者订阅（见 subscribeUnified）
            }
            Set<String> relayed = new LinkedHashSet<>();
            for (String t : props.getOutboxEventTypes()) {
                if (c.supports(t)) relayed.add(t);
            }
            if (!relayed.isEmpty()) {
                groups.computeIfAbsent(c.subscriptionGroup(), k -> new ArrayList<>()).add(c);
                groupTypes.computeIfAbsent(c.subscriptionGroup(), k -> new LinkedHashSet<>()).addAll(relayed);
            }
        }
        for (var g : groups.entrySet()) {
            broker.subscribe(new BrokerSubscription(g.getKey(), groupTypes.get(g.getKey()), true),
                    transactional(handler(g.getValue(), true)));
            count++;
        }
        if (unified) {
            count += subscribeUnified(broker);
        }
        log.info("[events] 已登记 {} 个中间件订阅；发件箱改由中间件投递的类型：{}", count, props.getOutboxEventTypes());
    }

    /**
     * 统一投递模式：门面里每个本地处理者一个保序订阅（按流键分区），并给门面装上中转，
     * 让运行器把名单里的“事件 × 处理者”投递改发中间件、目标就是这个处理者（ADR-067）。
     */
    private int subscribeUnified(MessageBroker broker) {
        if (props.getOutboxEventTypes().isEmpty()) {
            return 0;
        }
        UnifiedOutboxFacade facade = unifiedFacades == null ? null : unifiedFacades.getIfAvailable();
        if (facade == null || !facade.enabled(PLATFORM)) {
            throw new IllegalStateException("统一投递模式下配置了 shop.events.outbox-event-types，但没有可用的 PLATFORM 发件箱门面");
        }
        int count = 0;
        for (String id : facade.localHandlerIds(PLATFORM)) {
            OutboxHandler handler = facade.handler(PLATFORM, id);
            Set<String> relayed = new LinkedHashSet<>();
            for (String t : props.getOutboxEventTypes()) {
                if (handler.supports(t)) relayed.add(t);
            }
            if (!relayed.isEmpty()) {
                broker.subscribe(new BrokerSubscription(id, relayed, true), unifiedHandler(handler));
                count++;
            }
        }
        facade.setRelay(new BrokerRelay() {
            @Override public boolean relays(String eventType) { return props.relays(eventType); }
            @Override public void publish(OutboxEnvelope envelope, String handlerId) {
                broker.publish(BrokerMessage.of(envelope, handlerId));
            }
        });
        return count;
    }

    /** 处理结果映射成中间件动作：成功/警告确认；永久失败直接死信；可重试/结果未知退回重投。 */
    static Consumer<BrokerMessage> unifiedHandler(OutboxHandler handler) {
        return message -> {
            OutboxEnvelope envelope = message.toOutboxEnvelope();
            DeliveryResult[] result = new DeliveryResult[1];
            DeliveryContext.run(envelope.eventNo(), () -> result[0] = handler.handle(envelope));
            DeliveryResult r = result[0];
            switch (r == null ? DeliveryResult.Kind.OUTCOME_UNKNOWN : r.kind()) {
                case SENT, WARNING -> { }
                case PERMANENT -> throw new BrokerDeadLetterException(handler.handlerId() + " 永久失败："
                        + r.errorCode() + " " + r.detail());
                default -> throw new IllegalStateException(handler.handlerId() + " 暂时失败，退回重投："
                        + (r == null ? "null" : r.kind() + " " + r.errorCode()));
            }
        };
    }

    private Consumer<BrokerMessage> transactional(Consumer<BrokerMessage> handler) {
        TransactionTemplate tx = perMessage;
        if (tx == null) {
            return handler;
        }
        return message -> tx.executeWithoutResult(status -> handler.accept(message));
    }

    /** 组内按顺序逐个调支持该类型的消费者；任何一个抛错整条重投（已成功的靠自身幂等），与进程内投递一致。 */
    static Consumer<BrokerMessage> handler(List<OutboxConsumer> members, boolean fromOutbox) {
        return message -> {
            for (OutboxConsumer consumer : members) {
                deliverTo(consumer, message.toEnvelope(), fromOutbox);
            }
        };
    }

    private static void deliverTo(OutboxConsumer consumer, SysOutbox envelope, boolean fromOutbox) {
        if (envelope.getEventType() == null || !consumer.supports(envelope.getEventType())) {
            return;
        }
        Runnable call = () -> DeliveryContext.run(envelope.getEventNo(), () -> consumer.consume(envelope));
        if (fromOutbox) {
            // 与进程内投递一致：消费里调支付，来源记成这个事件类型
            CallSources.asOutbox(envelope.getEventType(), () -> {
                call.run();
                return null;
            });
        } else {
            call.run();
        }
    }

    @Override
    public void stop() { running = false; }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return PHASE; }
}
