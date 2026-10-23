package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryCheckoutPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.trade.service.IndustryCheckoutService;
import org.springframework.stereotype.Component;

@Component
public class IndustryCheckoutPortImpl implements IndustryCheckoutPort {
    private final IndustryCheckoutService service;
    public IndustryCheckoutPortImpl(IndustryCheckoutService service) { this.service = service; }
    @Override public View get(Identity identity, String orderNo) { return service.get(identity, orderNo); }
    @Override public View pay(PayCommand command) { return service.pay(command); }
    @Override public View queryPayment(Identity identity, String orderNo) { return service.queryPayment(identity, orderNo); }
    @Override public View finalizeCheckout(Identity identity, String orderNo) { return service.finalizeCheckout(identity, orderNo); }
}
