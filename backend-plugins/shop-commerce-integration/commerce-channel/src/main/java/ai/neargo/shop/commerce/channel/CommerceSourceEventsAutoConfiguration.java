package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.service.SourceNoticeService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

/**
 * Default-off local/remote notification transport does not make product writes depend on a live
 * channel.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceNoticeClientAutoConfiguration"
        })
@ConditionalOnProperty(name = "shop.commerce.events.enabled", havingValue = "true")
public class CommerceSourceEventsAutoConfiguration {
    @Bean
    @ConditionalOnBean({SourceNoticeService.class, ObjectMapper.class})
    CommerceProductSourceEventConsumer commerceProductSourceEventConsumer(
            SourceNoticeService service, ObjectMapper json) {
        return new CommerceProductSourceEventConsumer(service, json);
    }
}
