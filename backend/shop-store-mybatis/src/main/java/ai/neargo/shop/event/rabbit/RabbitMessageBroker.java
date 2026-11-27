package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.broker.BrokerDeadLetterException;
import ai.neargo.shop.event.broker.BrokerException;
import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerSubscription;
import ai.neargo.shop.event.broker.BrokerSubscriptions;
import ai.neargo.shop.event.broker.MessageBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * {@link MessageBroker} 的 RabbitMQ 4 实现。所有 AMQP 相关代码只在本包（守卫测试 {@code BrokerIsolationTest}）。
 *
 * <p>发布：持久消息 + 发布确认 + 不可路由退回，三者都过才算接收；否则抛 {@link BrokerException}。
 * <p>订阅：按 {@link RabbitTopology} 建队列，每条队列一个监听容器；处理器抛错即退回重投，仲裁队列超投递上限转死信。
 * broker 暂时连不上不阻止启动：声明失败只记日志，连接恢复后再声明，容器自己重连。
 */
public class RabbitMessageBroker implements MessageBroker, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessageBroker.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final CachingConnectionFactory connections;
    private final RabbitTemplate template;
    private final RabbitEventsProperties props;
    private final ObjectMapper json;
    private final List<Registered> subscriptions = new ArrayList<>();
    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();
    private volatile boolean running;

    private record Registered(BrokerSubscription subscription, Consumer<BrokerMessage> handler) { }

    public RabbitMessageBroker(CachingConnectionFactory connections, RabbitEventsProperties props, ObjectMapper json) {
        this.connections = connections;
        this.props = props;
        this.json = json;
        this.template = new RabbitTemplate(connections);
        this.template.setExchange(props.getExchange());
        this.template.setMandatory(true);
    }

    @Override
    public void publish(BrokerMessage message) {
        CorrelationData correlation = new CorrelationData(message.eventNo());
        try {
            template.send(props.getExchange(), EventMessages.routingKey(message), EventMessages.toMessage(message, json),
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.ack()) {
                throw new BrokerException("RabbitMQ 拒收 " + message.eventType() + " " + message.eventNo()
                        + "：" + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new BrokerException("RabbitMQ 没有订阅接收 " + message.eventType() + " " + message.eventNo()
                        + "（不可路由，队列未建或未绑定）");
            }
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            throw new BrokerException("RabbitMQ 未确认接收 " + message.eventType() + " " + message.eventNo(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerException("等待 RabbitMQ 确认被中断", e);
        }
    }

    @Override
    public synchronized void subscribe(BrokerSubscription subscription, Consumer<BrokerMessage> handler) {
        Registered r = new Registered(subscription, handler);
        subscriptions.add(r);
        if (running) {
            RabbitAdmin admin = new RabbitAdmin(connections);
            declare(admin, List.of(r));
            startContainers(admin, r);
        }
    }

    @Override
    public synchronized void start() {
        RabbitAdmin admin = new RabbitAdmin(connections);
        List<Registered> snapshot = List.copyOf(subscriptions);
        declare(admin, snapshot);
        connections.addConnectionListener(connection -> declare(admin, List.copyOf(subscriptions)));
        snapshot.forEach(r -> startContainers(admin, r));
        running = true;
        log.info("[events] RabbitMQ 已启动：{} 个订阅、{} 条队列", snapshot.size(), containers.size());
    }

    private void startContainers(RabbitAdmin admin, Registered r) {
        for (String queue : RabbitTopology.listenQueues(r.subscription(), props)) {
            SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(connections);
            c.setQueueNames(queue);
            c.setMessageListener(m -> {
                try {
                    r.handler().accept(EventMessages.fromMessage(m, json));
                } catch (BrokerDeadLetterException e) {
                    // 永久失败：不重投，直接转该订阅的死信
                    throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
                }
            });
            c.setAcknowledgeMode(AcknowledgeMode.AUTO);
            c.setDefaultRequeueRejected(true);
            c.setConcurrentConsumers(r.subscription().ordered() ? 1 : Math.max(1, props.getConcurrency()));
            c.setPrefetchCount(r.subscription().ordered() ? 1 : 10);
            c.setMissingQueuesFatal(false);
            c.setAmqpAdmin(admin);
            c.start();
            containers.add(c);
        }
    }

    private void declare(RabbitAdmin admin, List<Registered> list) {
        try {
            for (Registered r : list) {
                Declarables d = RabbitTopology.forSubscription(r.subscription(), props);
                d.getDeclarablesByType(Exchange.class).forEach(admin::declareExchange);
                d.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
                d.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);
            }
        } catch (AmqpException e) {
            log.warn("[events] RabbitMQ 拓扑声明失败，连接恢复后重试：{}", e.toString());
        }
    }

    @Override
    public synchronized void stop() {
        containers.forEach(SimpleMessageListenerContainer::stop);
        containers.clear();
        running = false;
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return BrokerSubscriptions.PHASE + 1; }
}
