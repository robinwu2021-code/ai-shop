package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.service.TaxSourceNoticeService;
import ai.neargo.shop.spi.commerce.TaxSourceNoticeInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

@AutoConfiguration(
        afterName = "ai.neargo.shop.commerce.config.CommerceTaxSourceNoticeAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.notice-client.enabled", havingValue = "true")
@ConditionalOnMissingBean(TaxSourceNoticeService.class)
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceTaxNoticeClientAutoConfiguration {
    @Bean
    @Lazy
    TaxSourceNoticeInternalApi taxSourceNoticeInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.COMMERCE, TaxSourceNoticeInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    TaxSourceNoticeService remoteTaxSourceNoticeService(
            ObjectProvider<TaxSourceNoticeInternalApi> api) {
        return notice -> {
            var receipt =
                    ServiceCalls.call(ServiceName.COMMERCE, () -> api.getObject().receive(notice));
            if (receipt == null
                    || receipt.noticeNo() == null
                    || receipt.noticeNo().isBlank()
                    || receipt.readyAt() == null)
                throw new IllegalStateException("Missing durable tax source notice");
            return receipt;
        };
    }
}
