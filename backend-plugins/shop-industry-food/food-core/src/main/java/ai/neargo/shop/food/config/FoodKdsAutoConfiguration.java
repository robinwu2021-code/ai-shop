package ai.neargo.shop.food.config;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.mapper.FoodStationRuntimeMapper;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
import ai.neargo.shop.food.service.impl.FoodKdsServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStationRuntimeServiceImpl;
import ai.neargo.shop.spi.printing.PrintPort;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

import javax.sql.DataSource;

/** Registers persistent KDS ownership only where the FOOD primary database is local. */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodAutoConfiguration",
            "ai.neargo.shop.food.channel.config.FoodPrintingClientConfiguration"
        })
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodKdsAutoConfiguration {
    @Bean
    FoodStationRuntimeMapper foodStationRuntimeMapper(@Qualifier("dataSource") DataSource source) {
        return new FoodStationRuntimeMapper(new JdbcTemplate(source));
    }

    @Bean
    FoodStationRuntimeService foodStationRuntimeService(
            FoodStationRuntimeMapper mapper,
            FoodKdsMapper kdsMapper,
            FoodKdsService kds,
            PrintPort printing,
            @Qualifier("foodOutboxPublisher") OutboxPublisher outbox,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json) {
        return new FoodStationRuntimeServiceImpl(
                mapper, kdsMapper, kds, printing, outbox, manager, Clock.systemUTC(), json);
    }

    @Bean
    FoodEventPrintDocumentAssembler foodEventPrintDocumentAssembler(
            FoodKdsMapper mapper, ObjectMapper json) {
        return new FoodEventPrintDocumentAssembler(mapper, json);
    }

    @Bean
    @ConditionalOnMissingBean(FoodKdsMapper.class)
    FoodKdsMapper foodKdsMapper(@Qualifier("dataSource") DataSource source) {
        return new FoodKdsMapper(new JdbcTemplate(source));
    }

    @Bean
    @ConditionalOnMissingBean(FoodKdsService.class)
    @ConditionalOnBean(PrintPort.class)
    FoodKdsService foodKdsService(
            FoodKdsMapper mapper,
            PrintPort printing,
            ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new FoodKdsServiceImpl(mapper, printing, json, manager, Clock.systemUTC());
    }
}
