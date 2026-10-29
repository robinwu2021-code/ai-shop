package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.service.MenuWithdrawalSourceService;
import ai.neargo.shop.food.service.impl.MenuWithdrawalSourceServiceImpl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@AutoConfiguration(after = FoodAutoConfiguration.class)
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.commerce.catalog.withdrawal.enabled"},
        havingValue = "true")
public class FoodMenuWithdrawalAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        MenuMapper.class,
        MenuStoreMapper.class,
        ConfigRevisionMapper.class,
        ActionLogMapper.class,
        ObjectMapper.class
    })
    MenuWithdrawalSourceService menuWithdrawalSourceService(
            MenuMapper menus,
            MenuStoreMapper stores,
            ConfigRevisionMapper revisions,
            ActionLogMapper actions,
            ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new MenuWithdrawalSourceServiceImpl(
                menus, stores, revisions, actions, json, manager, Clock.systemUTC());
    }
}
