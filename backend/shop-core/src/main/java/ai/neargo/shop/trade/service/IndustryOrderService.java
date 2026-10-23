package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

public interface IndustryOrderService {
    Result place(Command command, String key);
    Result cancel(Command command, String key);
    Result query(Identity identity, String key);
    Result get(Identity identity, String orderNo);
    Result getForStore(Scope scope, String actorNo, String orderNo);
}
