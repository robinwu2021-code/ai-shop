package ai.neargo.shop.event.broker;

import ai.neargo.shop.event.OutboxConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/** 中间件封装的公共部分，始终装配；没有 {@link MessageBroker} 实现时全部退化为进程内投递。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BrokerProperties.class)
public class EventBrokerConfiguration {

    @Bean
    BrokerSubscriptions brokerSubscriptions(ObjectProvider<MessageBroker> brokers,
                                            ObjectProvider<OutboxConsumer> consumers,
                                            BrokerProperties props, Environment env,
                                            ObjectProvider<PlatformTransactionManager> transactions) {
        var subscriptions = new BrokerSubscriptions(brokers, consumers, props, env);
        // 与 OutboxDispatcher 同一个事务管理器（主库那个）
        subscriptions.setTransactionManager(transactions.getIfAvailable());
        return subscriptions;
    }
}
