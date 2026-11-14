package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.FoodMappers.BusinessDayMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.DiningReportFactsService;
import ai.neargo.shop.food.service.DiningReportService;
import ai.neargo.shop.food.service.impl.DiningReportServiceImpl;
import ai.neargo.shop.spi.report.ReportQueryPort;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.food.port.DiningReportFactsPortAdapter;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(afterName="ai.neargo.shop.food.config.FoodAutoConfiguration")
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class DiningReportFactsConfiguration {
    @Bean @ConditionalOnMissingBean(DiningReportService.class)
    DiningReportService diningReportService(ObjectProvider<ReportQueryPort> reports,StoreCardQueryPort stores,
            ObjectProvider<ReportRealtimePort> realtime,ObjectProvider<ReportMetricCatalogPort> catalog,ObjectProvider<ReportExportPort> exports) {
        return new DiningReportServiceImpl(reports,stores,realtime,catalog,exports);
    }
    @Bean @ConditionalOnMissingBean(DiningReportFactsService.class)
    DiningReportFactsService diningReportFactsService(BusinessDayMapper days,StorePolicyMapper policies,
            OrderBatchMapper batches,CoreOrderBindingMapper bindings,OrderLineMapper lines,StoreCardQueryPort stores,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DiningReportFactsPortAdapter(days,policies,batches,bindings,lines,stores,manager);
    }
}
