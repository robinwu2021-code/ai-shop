package ai.neargo.shop.spi.trade;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface IndustryOrderAmendmentInternalApi {
    String ROOT = "/internal/trade/industry-amendment";

    record Amend(IndustryOrderAmendmentPort.Command command, String key) {}

    record Fee(IndustryOrderAmendmentPort.FeeScopeAdjustment command, String key) {}

    record Query(IndustryFulfillmentPort.Reference reference, String actorNo, String key) {}

    @PostExchange(ROOT + "/amend")
    IndustryOrderAmendmentPort.Receipt amend(@RequestBody Amend command);

    @PostExchange(ROOT + "/fee")
    IndustryOrderAmendmentPort.Receipt fee(@RequestBody Fee command);

    @PostExchange(ROOT + "/query")
    IndustryOrderAmendmentPort.Receipt query(@RequestBody Query command);
}
