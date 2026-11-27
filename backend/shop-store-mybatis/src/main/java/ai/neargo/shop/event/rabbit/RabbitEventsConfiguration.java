package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.NotificationTransport;
import ai.neargo.shop.event.OutboxConsumer;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code shop.events.transport=rabbitmq} 时才装配（ADR-067 决策 7）；默认 {@code local} 下这里什么都不建、不连。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.events.transport", havingValue = "rabbitmq")
@EnableConfigurationProperties(RabbitEventsProperties.class)
public class RabbitEventsConfiguration {

    @Bean(destroyMethod = "destroy")
    CachingConnectionFactory shopEventsConnectionFactory(RabbitEventsProperties props) {
        props.validate();
        CachingConnectionFactory factory = new CachingConnectionFactory(props.getHost(), props.getPort());
        factory.setVirtualHost(props.getVirtualHost());
        factory.setUsername(props.getUsername());
        factory.setPassword(props.getPassword());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        factory.setConnectionNameStrategy(f -> "ai-shop-events");
        return factory;
    }

    @Bean
    RabbitTemplate shopEventsRabbitTemplate(CachingConnectionFactory shopEventsConnectionFactory,
                                            RabbitEventsProperties props) {
        RabbitTemplate template = new RabbitTemplate(shopEventsConnectionFactory);
        template.setExchange(props.getExchange());
        template.setMandatory(true);
        return template;
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService shopEventsSendExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(1, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000), r -> {
            Thread t = new Thread(r, "events-mq-send-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    NotificationTransport rabbitNotificationTransport(RabbitTemplate shopEventsRabbitTemplate, ObjectMapper json,
                                                      RabbitEventsProperties props,
                                                      ExecutorService shopEventsSendExecutor) {
        return new RabbitNotificationTransport(shopEventsRabbitTemplate, json, props.getSendAttempts(),
                props.getSendBackoffMillis(), shopEventsSendExecutor);
    }

    @Bean
    RabbitNotificationRuntime rabbitNotificationRuntime(CachingConnectionFactory shopEventsConnectionFactory,
                                                        ObjectProvider<OutboxConsumer> consumers,
                                                        RabbitEventsProperties props, ObjectMapper json) {
        return new RabbitNotificationRuntime(shopEventsConnectionFactory, consumers, props, json);
    }
}
