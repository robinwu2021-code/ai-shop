package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Internal source-bound commands, never a public API accepting a caller-supplied buyer. */
public interface IndustryOrderInternalApi {
    String ROOT = "/internal/trade/industry-orders";
    @PostExchange(ROOT + "/place") Result place(@RequestBody Place request);
    @PostExchange(ROOT + "/query") Result query(@RequestBody Query request);
    @PostExchange(ROOT + "/get") Result get(@RequestBody Get request);
    @PostExchange(ROOT + "/cancel") Result cancel(@RequestBody Place request);
    @PostExchange(ROOT + "/store/get") Result getForStore(@RequestBody StoreGet request);
    record StoreGet(Scope scope, String actorNo, String orderNo) { }
    record Place(Command command, String key) {
        @Override public String toString() { return "IndustryOrderPlace[REDACTED]"; }
    }
    record Query(Identity identity, String key) { }
    record Get(Identity identity, String orderNo) { }
}
