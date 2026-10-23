package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.IndustryCheckoutPort.PayCommand;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.View;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface IndustryCheckoutInternalApi {
    String ROOT = "/internal/trade/industry-checkouts";
    record Reference(Identity identity, String orderNo) { }
    @PostExchange(ROOT + "/get") View get(@RequestBody Reference request);
    @PostExchange(ROOT + "/pay") View pay(@RequestBody PayCommand command);
    @PostExchange(ROOT + "/query-payment") View queryPayment(@RequestBody Reference request);
    @PostExchange(ROOT + "/finalize") View finalizeCheckout(@RequestBody Reference request);
}
