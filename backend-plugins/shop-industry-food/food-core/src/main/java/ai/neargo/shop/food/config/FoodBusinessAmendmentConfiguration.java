package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.mapper.FoodReturnAmendmentMapper;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.service.impl.BusinessReturnApprovalServiceImpl;
import ai.neargo.shop.food.port.FoodAmendmentSourcePortImpl;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

@AutoConfiguration(after = FoodOperationsConfiguration.class)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodBusinessAmendmentConfiguration {
    @Bean
    IndustryAmendmentSourcePort foodAmendmentSource(
            ReturnMapper returns,
            ReturnItemMapper items,
            OrderBatchMapper batches,
            CoreOrderBindingMapper bindings,
            FoodReturnAmendmentMapper intents,
            ObjectMapper json) {
        return new FoodAmendmentSourcePortImpl(returns, items, batches, bindings, intents, json);
    }

    @Bean
    BusinessReturnApprovalService businessReturnApproval(
            FoodReturnAmendmentMapper intents,
            ReturnMapper returns,
            OrderBatchMapper batches,
            CoreOrderBindingMapper bindings,
            CheckCartMapper carts,
            IndustryAmendmentSourcePort source,
            ObjectProvider<IndustryOrderAmendmentPort> owner,
            ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new BusinessReturnApprovalServiceImpl(
                intents, returns, batches, bindings, carts, source, owner, json, manager);
    }

    @ConditionalOnProperty(prefix = "shop.job", name = "enabled", havingValue = "true")
    @Bean
    BusinessReturnAmendmentRecoveryJob businessReturnAmendmentRecoveryJob(
            BusinessReturnApprovalService service) {
        return new BusinessReturnAmendmentRecoveryJob(service);
    }

    public static class BusinessReturnAmendmentRecoveryJob {
        private final BusinessReturnApprovalService service;

        BusinessReturnAmendmentRecoveryJob(BusinessReturnApprovalService s) {
            service = s;
        }

        @Scheduled(fixedDelayString = "${shop.food.return-amendment.recovery-delay-ms:15000}")
        public void run() {
            service.recover(50);
        }
    }
}
