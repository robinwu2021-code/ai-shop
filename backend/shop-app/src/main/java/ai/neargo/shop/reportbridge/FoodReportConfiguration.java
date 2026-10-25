package ai.neargo.shop.reportbridge;
import ai.neargo.shop.reportbridge.port.DeferredReportDayPort;
import ai.neargo.shop.reportbridge.port.ReportRealtimePortAdapter;

import java.time.Clock;
import ai.neargo.shop.report.config.ReportStoreProperties;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.report.support.ReportMetricCalculator;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.reportbridge.service.ReportDailyService;
import ai.neargo.shop.reportbridge.service.ReportExportService;
import ai.neargo.shop.reportbridge.service.ReportSourceCollectionService;
import ai.neargo.shop.reportbridge.service.impl.ReportDailyServiceImpl;
import ai.neargo.shop.reportbridge.service.impl.ReportSourceCollectionServiceImpl;
import ai.neargo.shop.spi.report.ReportDayPort;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import java.time.LocalDate;
import ai.neargo.shop.reportbridge.service.ReportRealtimeService;
import ai.neargo.shop.reportbridge.service.impl.ReportRealtimeServiceImpl;
import ai.neargo.shop.spi.report.ReportSourceFactsPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.reportbridge.port.RemotePaymentReportFactsPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"shop.report.enabled","shop.report.food.enabled"},havingValue="true")
public class FoodReportConfiguration {
    @Bean ReportSourceFactsPort paymentReportFactsPort(ObjectProvider<InternalHttp> http) { return new RemotePaymentReportFactsPort(http); }
    @Bean ReportSourceCollectionService reportSourceCollectionService(ObjectProvider<ReportSourceFactsPort> ports) {
        return new ReportSourceCollectionServiceImpl(ports.orderedStream().toList());
    }
    @Bean ReportRealtimePortAdapter reportRealtimePortAdapter(ReportRealtimeService service) {return new ReportRealtimePortAdapter(service);}
    @Bean ReportRealtimeService reportRealtimeService(ReportSourceCollectionService sources,ObjectProvider<ReportDayPort> ports,
            ReportMetricCalculator calculator,ReportStoreProperties props) {
        return new ReportRealtimeServiceImpl(sources,days(ports),calculator,props.getFood().getRowLimit(),props.getFood().getMetricVersion());
    }
    private static ReportDayPort days(ObjectProvider<ReportDayPort> ports) {
        return new DeferredReportDayPort(ports);
    }

    @Bean ReportDailyService reportDailyService(ReportStorageService storage,ReportSourceCollectionService sources,
            ObjectProvider<ReportDayPort> dayPorts,ReportMetricCatalog catalog,ReportMetricCalculator calculator,ReportStoreProperties props,
            ObjectProvider<ReportExportService> exports) {
        ReportDayPort days=days(dayPorts);
        var service=new ReportDailyServiceImpl(storage,sources,days,catalog,calculator,props.getFood(),Clock.systemUTC());
        service.setMaintenance((tenant,entity)-> {
            var available=exports.getIfUnique();if(available!=null) available.maintain(tenant,entity,20);
        });
        return service;
    }
}
