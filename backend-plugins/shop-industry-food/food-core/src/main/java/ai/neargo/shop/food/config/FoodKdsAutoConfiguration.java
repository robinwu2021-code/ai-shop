package ai.neargo.shop.food.config;

import ai.neargo.shop.common.config.ModuleDataSources;

import ai.neargo.shop.event.broker.MessageBroker;
import ai.neargo.shop.food.service.support.BrokerFoodDeviceRefresh;
import ai.neargo.shop.food.spi.FoodDeviceRefresh;
import ai.neargo.shop.food.spi.InProcessFoodDeviceRefresh;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.mapper.FoodStationRuntimeMapper;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
import ai.neargo.shop.food.service.impl.FoodKdsServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStationRuntimeServiceImpl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

import javax.sql.DataSource;

/** Registers persistent KDS ownership only where the FOOD primary database is local. */
@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodAutoConfiguration")
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodKdsAutoConfiguration {
    @Bean
    FoodStationRuntimeMapper foodStationRuntimeMapper(@Qualifier(ModuleDataSources.INDUSTRY_DATA_SOURCE) DataSource source) {
        return new FoodStationRuntimeMapper(new JdbcTemplate(source));
    }

    /** 设备刷新信号（TDD §2.8 D-3）：配了消息中间件就广播到每个实例，否则只在本进程内传递。 */
    @Bean
    @ConditionalOnMissingBean(FoodDeviceRefresh.class)
    FoodDeviceRefresh foodDeviceRefresh(ObjectProvider<MessageBroker> broker, ObjectMapper json) {
        MessageBroker available = broker.getIfAvailable();
        return available == null
                ? new InProcessFoodDeviceRefresh()
                : new BrokerFoodDeviceRefresh(available, json);
    }

    @Bean
    FoodStationRuntimeService foodStationRuntimeService(
            FoodStationRuntimeMapper mapper,
            FoodKdsMapper kdsMapper,
            FoodKdsService kds,
            @Qualifier(ModuleDataSources.INDUSTRY_TX_MANAGER) PlatformTransactionManager manager,
            ObjectMapper json,
            FoodDeviceRefresh refresh) {
        var service = new FoodStationRuntimeServiceImpl(
                mapper, kdsMapper, kds, manager, Clock.systemUTC(), json);
        service.setDeviceRefresh(refresh);
        return service;
    }

    @Bean
    @ConditionalOnMissingBean(FoodKdsMapper.class)
    FoodKdsMapper foodKdsMapper(@Qualifier(ModuleDataSources.INDUSTRY_DATA_SOURCE) DataSource source) {
        return new FoodKdsMapper(new JdbcTemplate(source));
    }

    @Bean
    @ConditionalOnMissingBean(FoodKdsService.class)
    FoodKdsService foodKdsService(
            FoodKdsMapper mapper,
            ObjectMapper json,
            @Qualifier(ModuleDataSources.INDUSTRY_TX_MANAGER) PlatformTransactionManager manager) {
        return new FoodKdsServiceImpl(mapper, json, manager, Clock.systemUTC());
    }
}
