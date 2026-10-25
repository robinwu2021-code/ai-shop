package ai.neargo.shop.spi.report;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface ReportPaymentFactsInternalApi {
    String SNAPSHOT="/internal/pay/report-facts/snapshot";
    @PostExchange(SNAPSHOT) ReportContracts.SourceSnapshot snapshot(@RequestBody ReportContracts.SourceQuery query);
}
