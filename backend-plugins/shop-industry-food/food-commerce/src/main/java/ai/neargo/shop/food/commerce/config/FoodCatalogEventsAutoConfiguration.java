package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.food.commerce.event.FoodCatalogSourceEventConsumer;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

/** Default-off consumption cannot break original FOOD publication when CIM is not installed. */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceNoticeClientAutoConfiguration"
        })
@ConditionalOnProperty(name = "shop.food.commerce.events.enabled", havingValue = "true")
public class FoodCatalogEventsAutoConfiguration {
    @Bean
    @ConditionalOnBean({SourceNoticeService.class, ObjectMapper.class})
    FoodCatalogSourceEventConsumer foodCatalogSourceEventConsumer(
            SourceNoticeService service, ObjectMapper json) {
        return new FoodCatalogSourceEventConsumer(service, json);
    }
}
