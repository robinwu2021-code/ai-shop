package ai.neargo.shop.spi.trade;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Service-authenticated source-owner transport; public clients never supply the trusted identity. */
public interface IndustryAfterSaleInternalApi {
    String APPLY = "/internal/trade/industry-after-sales/apply";
    String QUERY_APPLY = "/internal/trade/industry-after-sales/query-apply";
    String GET = "/internal/trade/industry-after-sales/get";
    String CANCEL = "/internal/trade/industry-after-sales/cancel";

    @PostExchange(APPLY)
    IndustryAfterSalePort.Result apply(@RequestBody IndustryAfterSalePort.Apply request);
    @PostExchange(QUERY_APPLY)
    IndustryAfterSalePort.Lookup queryApply(@RequestBody IndustryAfterSalePort.QueryApply query);
    @PostExchange(GET)
    IndustryAfterSalePort.Result get(@RequestBody IndustryAfterSalePort.Reference reference);
    @PostExchange(CANCEL)
    IndustryAfterSalePort.Result cancel(@RequestBody IndustryAfterSalePort.Reference reference);
}
