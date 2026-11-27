package ai.neargo.shop.event.broker;

import ai.neargo.shop.event.DeliveryContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.pay.CallSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;

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
    private volatile boolean running;

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
        if (!props.getOutboxEventTypes().isEmpty()
                && "unified".equalsIgnoreCase(env.getProperty("shop.outbox.sources.platform.mode", "legacy"))) {
            throw new IllegalStateException("shop.events.outbox-event-types 只支持旧投递模式；"
                    + "shop.outbox.sources.platform.mode=unified 时两套投递会抢同一批事件");
        }
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
                    handler(g.getValue(), true));
            count++;
        }
        log.info("[events] 已登记 {} 个中间件订阅；发件箱改由中间件投递的类型：{}", count, props.getOutboxEventTypes());
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
