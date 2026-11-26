package ai.neargo.shop.printing.config;

import ai.neargo.shop.common.config.ModuleDataSources;
import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.shop.printing.mapper.PrintMappers.AttemptMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.RouteMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.TemplateMapper;
import ai.neargo.shop.printing.service.PrintService;
import ai.neargo.shop.printing.port.PrintPortImpl;
import ai.neargo.shop.spi.printing.PrintPort;
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
    @Bean PrintDataScopeRegistration printDataScopeRegistration() { return new PrintDataScopeRegistration(); }

    @Bean
    @ConditionalOnMissingBean(PrintPort.class)
    PrintPort printPort(PrintService service) { return new PrintPortImpl(service); }

    @Bean
    @ConditionalOnMissingBean(PrintService.class)
    PrintService printService(DeviceMapper devices,TemplateMapper templates,RouteMapper routes,
            JobMapper jobs,AttemptMapper attempts,ObjectMapper json,
            @Qualifier(ModuleDataSources.PRINT_TX_MANAGER) PlatformTransactionManager manager) {
        return new PrintServiceImpl(devices,templates,routes,jobs,attempts,json,Clock.systemUTC(),manager);
    }
}
