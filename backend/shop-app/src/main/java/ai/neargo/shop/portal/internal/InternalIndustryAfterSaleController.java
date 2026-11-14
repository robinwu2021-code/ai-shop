package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.IndustryAfterSaleInternalApi;
import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalIndustryAfterSaleController {
    private final IndustryAfterSalePort afterSales;
    private final String token;
    public InternalIndustryAfterSaleController(IndustryAfterSalePort afterSales,
            @Value("${shop.services.internal-token:}") String token) {
        this.afterSales = afterSales;
        this.token = token;
    }
    @PostMapping(IndustryAfterSaleInternalApi.APPLY)
    public ResponseEntity<?> apply(@RequestBody IndustryAfterSalePort.Apply request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> afterSales.apply(request));
    }
    @PostMapping(IndustryAfterSaleInternalApi.QUERY_APPLY)
    public ResponseEntity<?> queryApply(@RequestBody IndustryAfterSalePort.QueryApply query,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> afterSales.queryApply(query));
    }
    @PostMapping(IndustryAfterSaleInternalApi.GET)
    public ResponseEntity<?> get(@RequestBody IndustryAfterSalePort.Reference reference,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> afterSales.get(reference));
    }
    @PostMapping(IndustryAfterSaleInternalApi.CANCEL)
    public ResponseEntity<?> cancel(@RequestBody IndustryAfterSalePort.Reference reference,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> afterSales.cancel(reference));
    }
}
