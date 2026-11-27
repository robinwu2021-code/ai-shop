package ai.neargo.shop.event.broker;

import ai.neargo.shop.event.OutboxConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 中间件封装的公共部分，始终装配；没有 {@link MessageBroker} 实现时全部退化为进程内投递。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BrokerProperties.class)
public class EventBrokerConfiguration {

    @Bean
    BrokerSubscriptions brokerSubscriptions(ObjectProvider<MessageBroker> brokers,
                                            ObjectProvider<OutboxConsumer> consumers,
                                            BrokerProperties props, Environment env) {
        return new BrokerSubscriptions(brokers, consumers, props, env);
    }
}
