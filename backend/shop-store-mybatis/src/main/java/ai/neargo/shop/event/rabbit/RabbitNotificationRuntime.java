package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.OutboxConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用就绪后为每个通知类消费者（{@link OutboxConsumer#afterCommitOnly()}）声明队列、起监听容器。
 *
 * <p>放在 {@link SmartLifecycle} 而不是 {@code @Bean} 里取消费者：消费者里有依赖发布总线的，
 * 装配期取会成环（见 {@code OutboxEventBus#pumpProvider} 的注释）。
 * broker 暂时连不上不阻止启动：声明失败只记日志，连接恢复时再声明一次；容器自己会重连。
 */
class RabbitNotificationRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RabbitNotificationRuntime.class);

    private final ConnectionFactory connections;
    private final ObjectProvider<OutboxConsumer> consumers;
    private final RabbitEventsProperties props;
    private final ObjectMapper json;
    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();
    private final List<Declarables> topology = new ArrayList<>();
    private volatile boolean running;

    RabbitNotificationRuntime(ConnectionFactory connections, ObjectProvider<OutboxConsumer> consumers,
                              RabbitEventsProperties props, ObjectMapper json) {
        this.connections = connections;
        this.consumers = consumers;
        this.props = props;
        this.json = json;
    }

    @Override
    public void start() {
        RabbitAdmin admin = new RabbitAdmin(connections);
        for (OutboxConsumer consumer : consumers.orderedStream().filter(OutboxConsumer::afterCommitOnly).toList()) {
            String handler = consumer.handlerId();
            topology.add(RabbitTopology.forConsumer(handler, consumer.eventTypes(), props));
            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connections);
            container.setQueueNames(RabbitTopology.queueName(handler, props));
            container.setMessageListener(new RabbitNotificationListener(consumer, json));
            container.setAcknowledgeMode(AcknowledgeMode.AUTO);
            container.setDefaultRequeueRejected(true);  // 抛错 → 退回重投，仲裁队列按投递上限转死信
            container.setConcurrentConsumers(Math.max(1, props.getConcurrency()));
            container.setMissingQueuesFatal(false);
            container.setAmqpAdmin(admin);
            containers.add(container);
        }
        declare(admin);
        connections.addConnectionListener(connection -> declare(admin));
        containers.forEach(SimpleMessageListenerContainer::start);
        running = true;
        log.info("[events] RabbitMQ 通知监听已启动：{} 条队列", containers.size());
    }

    private void declare(RabbitAdmin admin) {
        try {
            for (Declarables d : topology) {
                d.getDeclarables().forEach(x -> {
                    if (x instanceof Exchange e) admin.declareExchange(e);
                    else if (x instanceof Queue q) admin.declareQueue(q);
                    else if (x instanceof Binding b) admin.declareBinding(b);
                });
            }
        } catch (AmqpException e) {
            log.warn("[events] RabbitMQ 拓扑声明失败，连接恢复后重试：{}", e.toString());
        }
    }

    @Override
    public void stop() {
        containers.forEach(SimpleMessageListenerContainer::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
