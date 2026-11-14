package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.service.TaxSourceNoticeService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.commerce.config.CommerceTaxSourceNoticeAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceTaxNoticeClientAutoConfiguration"
        })
@ConditionalOnProperty(name = "shop.commerce.events.enabled", havingValue = "true")
public class CommerceTaxSourceEventsAutoConfiguration {
    @Bean
    @ConditionalOnBean({TaxSourceNoticeService.class, ObjectMapper.class})
    CommerceTaxSourceEventConsumer commerceTaxSourceEventConsumer(
            TaxSourceNoticeService service, ObjectMapper json) {
        return new CommerceTaxSourceEventConsumer(service, json);
    }
}
