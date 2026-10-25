package ai.neargo.shop.food.channel.config;

import java.time.Duration;
import ai.neargo.shop.spi.report.ReportQueryInternalApi;
import ai.neargo.shop.spi.report.ReportQueryPort;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.spi.report.ReportExportInternalApi;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.shop.svc.ReportServiceResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit standalone import; embedded FOOD keeps the local report service. */
@Configuration(proxyBeanMethods=false)
public class FoodReportClientConfiguration {
    @Bean ReportExportPort coreReportExportPort(InternalHttp http) {
        var api=http.client(ServiceName.PLATFORM,ReportExportInternalApi.class,Duration.ofSeconds(20));
        return new ReportExportPort() {
            public Task create(Create command) { return ReportServiceResponses.call(ServiceName.PLATFORM,()->api.create(command)); }
            public Task get(Reference reference) { return ReportServiceResponses.call(ServiceName.PLATFORM,()->api.get(reference)); }
            public Download download(Reference reference) { return ReportServiceResponses.call(ServiceName.PLATFORM,()->api.download(reference)); }
        };
    }
    @Bean ReportRealtimePort coreReportRealtimePort(InternalHttp http) {
        var api=http.client(ServiceName.PLATFORM,ReportQueryInternalApi.class,Duration.ofSeconds(15));
        return query->ReportServiceResponses.call(ServiceName.PLATFORM,()->api.realtime(query));
    }
    @Bean ReportMetricCatalogPort coreReportMetricCatalogPort(InternalHttp http) {
        var api=http.client(ServiceName.PLATFORM,ReportQueryInternalApi.class,Duration.ofSeconds(15));
        return ()->ReportServiceResponses.call(ServiceName.PLATFORM,api::metrics);
    }
    @Bean ReportQueryPort coreReportQueryPort(InternalHttp http) {
        var api=http.client(ServiceName.PLATFORM,ReportQueryInternalApi.class,Duration.ofSeconds(15));
        return query->ReportServiceResponses.call(ServiceName.PLATFORM,()->api.query(query));
    }
}
