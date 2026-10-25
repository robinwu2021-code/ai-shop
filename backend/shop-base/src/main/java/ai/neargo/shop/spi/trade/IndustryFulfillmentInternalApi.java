package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Receipt;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.VerifyPickup;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Service-token transport carries original-source proof and preserves the original operation key. */
public interface IndustryFulfillmentInternalApi {
    String ROOT="/internal/trade/industry-fulfillment";
    record Partial(IndustryFulfillmentPort.Fulfill command,String key) { }
    @PostExchange(ROOT+"/fulfill") Receipt fulfill(@RequestBody Partial command);
    @PostExchange(ROOT+"/pickup/verify") Receipt verifyPickup(@RequestBody Verify command);
    @PostExchange(ROOT+"/query") Receipt query(@RequestBody Query command);
    record Verify(VerifyPickup command,String key) {
        @Override public String toString() { return "IndustryPickupVerify[REDACTED]"; }
    }
    record Query(Reference reference,String actorNo,String key) { }
}
