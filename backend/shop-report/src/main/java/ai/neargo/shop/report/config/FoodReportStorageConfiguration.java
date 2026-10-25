package ai.neargo.shop.report.config;
import ai.neargo.shop.report.port.ReportQueryPortAdapter;

import ai.neargo.shop.report.dao.ReportBuildBatchDao;
import ai.neargo.shop.report.dao.ReportExportTaskDao;
import ai.neargo.shop.report.dao.ReportDayPublicationDao;
import ai.neargo.shop.report.dao.ReportDirtyDayDao;
import ai.neargo.shop.report.dao.ReportFoodMetricDailyDao;
import ai.neargo.shop.report.dao.ReportScopeCursorDao;
import ai.neargo.shop.report.dao.ReportSourceManifestDao;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.report.service.ReportQueryService;
import ai.neargo.shop.report.service.impl.ReportQueryServiceImpl;
import ai.neargo.shop.report.service.impl.ReportStorageServiceImpl;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.report.support.ReportMetricCalculator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"shop.report.enabled","shop.report.food.enabled"},havingValue="true")
public class FoodReportStorageConfiguration {
    @Bean ReportQueryPortAdapter reportQueryPortAdapter(ReportQueryService service) {return new ReportQueryPortAdapter(service);}
    @Bean ReportQueryService reportQueryService(ReportStorageService storage,ReportMetricCatalog catalog,ReportStoreProperties props,@Qualifier("reportTransactionManager") PlatformTransactionManager manager) {
        return new ReportQueryServiceImpl(storage,catalog,props.getFood().getRowLimit(),manager);
    }
    @Bean ReportMetricCatalog reportMetricCatalog() { return new ReportMetricCatalog(); }
    @Bean ReportMetricCalculator reportMetricCalculator(ReportMetricCatalog catalog) { return new ReportMetricCalculator(catalog); }
    @Bean ReportExportTaskDao reportExportTaskDao(@Qualifier("reportJdbcClient") JdbcClient jdbc) { return new ReportExportTaskDao(jdbc); }
    @Bean ReportBuildBatchDao reportBuildBatchDao(@Qualifier("reportJdbcClient") JdbcClient jdbc) { return new ReportBuildBatchDao(jdbc); }
    @Bean ReportDayPublicationDao reportDayPublicationDao(@Qualifier("reportJdbcClient") JdbcClient jdbc) { return new ReportDayPublicationDao(jdbc); }
    @Bean ReportDirtyDayDao reportDirtyDayDao(@Qualifier("reportJdbcClient") JdbcClient jdbc) { return new ReportDirtyDayDao(jdbc); }
    @Bean ReportFoodMetricDailyDao reportFoodMetricDailyDao(@Qualifier("reportJdbcClient") JdbcClient jdbc) { return new ReportFoodMetricDailyDao(jdbc); }
    @Bean ReportScopeCursorDao reportScopeCursorDao(@Qualifier("reportJdbcClient") JdbcClient jdbc) { return new ReportScopeCursorDao(jdbc); }
    @Bean ReportSourceManifestDao reportSourceManifestDao(@Qualifier("reportJdbcClient") JdbcClient jdbc) { return new ReportSourceManifestDao(jdbc); }
    @Bean ReportStorageService reportStorageService(ReportDayPublicationDao days,ReportBuildBatchDao builds,
            ReportSourceManifestDao sources,ReportFoodMetricDailyDao metrics,ReportDirtyDayDao dirty,ReportScopeCursorDao scopes,
            ReportMetricCatalog catalog,@Qualifier("reportTransactionManager") PlatformTransactionManager manager,ReportStoreProperties props) {
        return new ReportStorageServiceImpl(days,builds,sources,metrics,dirty,scopes,catalog,manager,
                props.getFood().getLeaseSeconds(),props.getFood().getRowLimit());
    }
}
