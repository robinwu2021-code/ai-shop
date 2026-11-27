package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.broker.MessageBroker;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code shop.events.transport=rabbitmq} 时提供 {@link MessageBroker} 的 RabbitMQ 实现（ADR-067）；
 * 默认 {@code local} 下这里什么都不建、不连。换中间件 = 换掉这个配置类与本包，其余代码不动。
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
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        factory.setConnectionNameStrategy(f -> "ai-shop-events");
        return factory;
    }

    @Bean
    RabbitMessageBroker rabbitMessageBroker(CachingConnectionFactory shopEventsConnectionFactory,
                                            RabbitEventsProperties props, ObjectMapper json) {
        return new RabbitMessageBroker(shopEventsConnectionFactory, props, json);
    }
}
