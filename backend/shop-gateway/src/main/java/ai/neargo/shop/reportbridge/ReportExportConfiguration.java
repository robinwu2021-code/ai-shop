package ai.neargo.shop.reportbridge;
import ai.neargo.shop.reportbridge.port.ReportExportPortAdapter;

import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.report.dao.ReportExportTaskDao;
import ai.neargo.shop.report.service.ReportQueryService;
import ai.neargo.shop.reportbridge.service.ReportArtifactStore;
import ai.neargo.shop.reportbridge.service.ReportExportService;
import ai.neargo.shop.reportbridge.service.impl.MediaReportArtifactStore;
import ai.neargo.shop.reportbridge.service.impl.ReportExportServiceImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"shop.report.enabled","shop.report.food.enabled","shop.report.export.enabled"},havingValue="true")
@ConditionalOnProperty(name="shop.report.access-role",havingValue="WRITER",matchIfMissing=true)
public class ReportExportConfiguration {
    @Bean ReportExportPortAdapter reportExportPortAdapter(ReportExportService service) {return new ReportExportPortAdapter(service);}
    @Bean(name="reportExportTaskExecutor",destroyMethod="shutdown")
    ThreadPoolTaskExecutor reportExportTaskExecutor() {
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(2);executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);executor.setThreadNamePrefix("report-export-");
        executor.setWaitForTasksToCompleteOnShutdown(false);executor.setAwaitTerminationSeconds(2);return executor;
    }
    @Bean ReportArtifactStore reportArtifactStore(ObjectProvider<MediaStore> stores) { return new MediaReportArtifactStore(stores); }
    @Bean ReportExportService reportExportService(ReportExportTaskDao tasks,ReportQueryService query,ReportArtifactStore files,
            BizIdentityResolver identities,@Qualifier("reportExportTaskExecutor") TaskExecutor executor,
            @Qualifier("reportTransactionManager") PlatformTransactionManager manager) {
        return new ReportExportServiceImpl(tasks,query,files,identities,executor,manager);
    }
}
