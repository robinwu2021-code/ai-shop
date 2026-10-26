package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.ServiceFormMapper;
import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.CheckCollaborationService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.FoodFeeSourceService;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.QueueNotificationService;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.food.service.ReservationPreorderService;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.ScheduledDiningDispatchService;
import ai.neargo.shop.food.service.ServiceFormService;
import ai.neargo.shop.food.lifecycle.ServiceSlotLifecycle;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.food.service.impl.BusinessOperationsServiceImpl;
import ai.neargo.shop.food.service.impl.CheckBusinessGuardServiceImpl;
import ai.neargo.shop.food.service.impl.CheckCollaborationServiceImpl;
import ai.neargo.shop.food.service.impl.FoodFeeSourceServiceImpl;
import ai.neargo.shop.food.service.impl.QueueNotificationServiceImpl;
import ai.neargo.shop.food.service.support.ReservationPreorderLifecycle;
import ai.neargo.shop.food.service.impl.ReservationPreorderServiceImpl;
import ai.neargo.shop.food.service.impl.ScheduledDiningDispatchServiceImpl;
import ai.neargo.shop.food.service.impl.ServiceFormServiceImpl;
import ai.neargo.shop.food.service.impl.ServiceSlotServiceImpl;
import ai.neargo.shop.food.spi.QueueNotificationPort;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

import javax.sql.DataSource;

@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodAutoConfiguration")
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodBusinessOperationsConfiguration {
    @Bean
    @ConditionalOnMissingBean(FoodBusinessOperationsMapper.class)
    FoodBusinessOperationsMapper foodBusinessOperationsMapper(
            @Qualifier("dataSource") DataSource source) {
        return new FoodBusinessOperationsMapper(source);
    }

    @Bean
    @ConditionalOnMissingBean(CheckBusinessGuardService.class)
    CheckBusinessGuardService checkBusinessGuardService(FoodBusinessOperationsMapper mapper) {
        return new CheckBusinessGuardServiceImpl(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(CheckCollaborationService.class)
    CheckCollaborationService checkCollaborationService(
            FoodBusinessOperationsMapper mapper,
            CheckBusinessGuardService guard,
            IdempotencyService idem,
            ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new CheckCollaborationServiceImpl(mapper, guard, idem, json, manager);
    }

    @Bean
    @ConditionalOnMissingBean(ReservationPreorderService.class)
    ReservationPreorderService reservationPreorderService(
            FoodBusinessOperationsMapper mapper,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            IdempotencyService idem,
            CheckCartService carts,
            DiningOrderService orders,
            ObjectMapper json) {
        return new ReservationPreorderServiceImpl(
                mapper, manager, idem, carts, orders, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(ServiceSlotService.class)
    ServiceSlotServiceImpl serviceSlotService(
            FoodBusinessOperationsMapper mapper, IdempotencyService idem, ObjectMapper json) {
        return new ServiceSlotServiceImpl(mapper, idem, Clock.systemUTC(), json);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessOperationsService.class)
    BusinessOperationsService businessOperationsService(
            FoodBusinessOperationsMapper mapper,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            IdempotencyService idem,
            ObjectMapper json,
            DiningOrderService orders,
            DiningReturnService returns,
            QueueService queues) {
        return new BusinessOperationsServiceImpl(
                mapper, manager, idem, json, orders, returns, queues);
    }

    @Bean
    @ConditionalOnMissingBean(ScheduledDiningDispatchService.class)
    ScheduledDiningDispatchService scheduledDiningDispatchService(
            FoodBusinessOperationsMapper mapper,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            KitchenTaskService kitchen,
            ObjectProvider<PrintPort> printing,
            FoodPrintService documents) {
        return new ScheduledDiningDispatchServiceImpl(
                mapper, manager, kitchen, printing::getIfAvailable, documents, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(ReservationPreorderLifecycle.class)
    ReservationPreorderLifecycle reservationPreorderLifecycle(
            FoodBusinessOperationsMapper mapper, ServiceSlotLifecycle slots) {
        return new ReservationPreorderLifecycle(mapper, slots);
    }

    @Bean
    @ConditionalOnMissingBean(QueueNotificationService.class)
    QueueNotificationServiceImpl queueNotificationService(
            FoodBusinessOperationsMapper mapper,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<QueueNotificationPort> provider) {
        return new QueueNotificationServiceImpl(
                mapper, manager, provider::getIfAvailable, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(FoodFeeSourceService.class)
    FoodFeeSourceServiceImpl foodFeeSourceService(
            FoodBusinessOperationsMapper mapper, ObjectMapper json) {
        return new FoodFeeSourceServiceImpl(mapper, json);
    }

    @Bean
    @ConditionalOnMissingBean(ServiceFormMapper.class)
    ServiceFormMapper serviceFormMapper(@Qualifier("dataSource") DataSource source) {
        return new ServiceFormMapper(source);
    }

    @Bean
    @ConditionalOnMissingBean(ServiceFormService.class)
    ServiceFormServiceImpl serviceFormService(
            ServiceFormMapper mapper,
            FoodBusinessOperationsMapper operations,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ServiceSlotService slots,
            ServiceSlotLifecycle capacity,
            ObjectProvider<ReservationService> reservations,
            ObjectProvider<StoreCardQueryPort> stores,
            ObjectMapper json) {
        return new ServiceFormServiceImpl(
                mapper,
                operations,
                manager,
                slots,
                capacity,
                reservations::getObject,
                numbers -> stores.getObject().cards(numbers),
                json,
                Clock.systemUTC());
    }
}
