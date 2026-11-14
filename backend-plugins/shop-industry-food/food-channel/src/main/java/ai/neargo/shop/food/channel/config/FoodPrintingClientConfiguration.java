package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CorePrintPortImpl;
import ai.neargo.shop.spi.printing.PrintInternalApi;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Only standalone FOOD imports this adapter; PLATFORM owns all shared printing tables. */
@Configuration(proxyBeanMethods=false)
public class FoodPrintingClientConfiguration {
    @Bean @Lazy PrintInternalApi printInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM,PrintInternalApi.class,Duration.ofSeconds(5));
    }
    @Bean @ConditionalOnMissingBean(PrintPort.class)
    PrintPort corePrintPort(ObjectProvider<PrintInternalApi> api) { return new CorePrintPortImpl(api::getObject); }
}
