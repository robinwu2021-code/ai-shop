package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.port.ChannelSourceValidationPortImpl;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/** Industry contribution precedes routing; the registry itself requires neither CIM persistence nor a data source. */
@AutoConfiguration(afterName = "ai.neargo.shop.food.commerce.config.FoodSourceValidationAutoConfiguration",
        before = CommerceCoreAutoConfiguration.class)
public class CommerceSourceValidationAutoConfiguration {
    @Bean
    @ConditionalOnBean(ChannelSourceValidationProvider.class)
    ChannelSourceValidationPort channelSourceValidationPort(List<ChannelSourceValidationProvider> providers,
            ObjectProvider<Clock> clocks, ListableBeanFactory beans) {
        for (String name : beans.getBeanNamesForType(ChannelSourceValidationPort.class, true, false)) {
            if (!"channelSourceValidationPort".equals(name))
                throw new IllegalStateException("Legacy source-validation Port cannot coexist with providers");
        }
        return new ChannelSourceValidationPortImpl(providers, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean(ChannelSourceValidationProvider.class)
    SmartInitializingSingleton sourceValidationUniqueness(ListableBeanFactory beans) {
        return () -> {
            var ports = beans.getBeansOfType(ChannelSourceValidationPort.class);
            if (ports.size() != 1 || !(ports.values().iterator().next() instanceof ChannelSourceValidationPortImpl))
                throw new IllegalStateException("Source-validation providers require one exclusive routing Port");
        };
    }
}
