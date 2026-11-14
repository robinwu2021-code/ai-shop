package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.IndustryCheckoutPort.PayCommand;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.View;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;

public interface IndustryCheckoutService {
    View get(Identity identity, String orderNo);
    View pay(PayCommand command);
    View queryPayment(Identity identity, String orderNo);
    View finalizeCheckout(Identity identity, String orderNo);
}
