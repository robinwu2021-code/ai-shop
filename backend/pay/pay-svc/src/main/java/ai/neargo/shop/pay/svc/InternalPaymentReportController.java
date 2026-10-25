package ai.neargo.shop.pay.svc;

import ai.neargo.shop.svc.ReportServiceResponses;
import org.springframework.web.bind.annotation.ExceptionHandler;

import ai.neargo.shop.pay.service.PaymentReportFactsService;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportPaymentFactsInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(name="shop.pay.report-facts.enabled",havingValue="true")
public class InternalPaymentReportController {
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> reportFailure(RuntimeException failure) {
        return ReportServiceResponses.error(failure);
    }

    private final PaymentReportFactsService facts;
    private final String token;
    public InternalPaymentReportController(PaymentReportFactsService facts,@Value("${shop.services.internal-token:}") String token) {
        this.facts=facts; this.token=token;
    }
    @PostMapping(ReportPaymentFactsInternalApi.SNAPSHOT)
    public ResponseEntity<?> snapshot(@RequestBody SourceQuery query,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->facts.readSnapshot(query));
    }
}
