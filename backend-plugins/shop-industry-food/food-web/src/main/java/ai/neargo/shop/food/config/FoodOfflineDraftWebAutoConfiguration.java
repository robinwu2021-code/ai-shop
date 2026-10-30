package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.mp.MpOfflineDraftController;
import ai.neargo.shop.food.service.OfflineDraftService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

@AutoConfiguration(
        after = FoodWebAutoConfiguration.class,
        afterName = "ai.neargo.shop.food.config.FoodOfflineDraftAutoConfiguration")
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.offline-drafts.enabled"},
        havingValue = "true")
@ConditionalOnBean(OfflineDraftService.class)
@Import(MpOfflineDraftController.class)
public class FoodOfflineDraftWebAutoConfiguration {}
