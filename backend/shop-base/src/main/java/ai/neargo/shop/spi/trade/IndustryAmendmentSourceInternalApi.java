package ai.neargo.shop.spi.trade;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface IndustryAmendmentSourceInternalApi {
    record Query(IndustryFulfillmentPort.Reference reference, String sourceReturnNo) {}

    @PostExchange("/internal/food/amendment-source/query")
    IndustryAmendmentSourcePort.Evidence get(@RequestBody Query query);
}
