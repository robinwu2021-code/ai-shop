package ai.neargo.shop.printing.config;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.event.outbox.runtime.OutboxTransactions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.shop.printing.mapper.PrintMappers.AttemptMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.RouteMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.TemplateMapper;
import ai.neargo.shop.printing.service.PrintService;
import ai.neargo.shop.printing.service.impl.PrintServiceImpl;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Shared platform printing is independent of whether the restaurant plugin is enabled. */
@Configuration(proxyBeanMethods=false)
public class PrintingConfiguration {
    @Bean("printingOutboxPublisher")
    OutboxPublisher printingOutboxPublisher(ObjectProvider<UnifiedOutboxFacade> facades,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        OutboxTransactions.register(manager);
        return (event,options) -> {
            var facade=facades.getIfAvailable();
            if(facade==null || !facade.enabled("PLATFORM")) {
                throw new IllegalStateException("Printing requires the registered PLATFORM outbox source");
            }
            return facade.publisher("PLATFORM").publish(event,options);
        };
    }

    @Bean PrintDataScopeRegistration printDataScopeRegistration() { return new PrintDataScopeRegistration(); }

    @Bean
    @ConditionalOnMissingBean(PrintService.class)
    PrintService printService(DeviceMapper devices,TemplateMapper templates,RouteMapper routes,
            JobMapper jobs,AttemptMapper attempts,ObjectMapper json,
            @Qualifier("printingOutboxPublisher") OutboxPublisher events,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new PrintServiceImpl(devices,templates,routes,jobs,attempts,json,Clock.systemUTC(),events,manager);
    }
}
