package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.service.IndustryOrderService;
import org.springframework.stereotype.Component;

@Component
public class IndustryOrderPortImpl implements IndustryOrderPort {
    private final IndustryOrderService service;
    public IndustryOrderPortImpl(IndustryOrderService service) { this.service = service; }
    @Override public Result acceptUnpaid(Identity identity,String orderNo) {
        return service.acceptUnpaid(identity,orderNo);
    }
    @Override public Result placeForDiningRoom(Command command, String key) {
        return service.placeForDiningRoom(command, key);
    }
    @Override public Result placeForService(Command command, String key, String fulfillment, boolean staff) {
        return service.placeForService(command, key, fulfillment, staff);
    }
    @Override public Result placeForStore(Command command, String key) { return service.placeForStore(command, key); }
    @Override public Result queryForStore(Identity identity, String key) { return service.queryForStore(identity, key); }
    @Override public Result place(Command command, String key) { return service.place(command, key); }
    @Override public Result cancelForStore(Command command, String key, String fulfillment) {
        return service.cancelForStore(command, key, fulfillment);
    }
    @Override public Result cancel(Command command, String key) { return service.cancel(command, key); }
    @Override public Result getForStore(Scope scope, String actor, String orderNo) { return service.getForStore(scope, actor, orderNo); }
    @Override public Result query(Identity identity, String key) { return service.query(identity, key); }
    @Override public Result get(Identity identity, String orderNo) { return service.get(identity, orderNo); }
}
