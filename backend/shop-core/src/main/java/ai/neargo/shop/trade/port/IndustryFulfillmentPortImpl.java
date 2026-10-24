package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.trade.service.IndustryFulfillmentService;
import org.springframework.stereotype.Component;

/** Only the original trade owner can verify code, authorize stock issue, and transition the original suborder. */
@Component
public class IndustryFulfillmentPortImpl implements IndustryFulfillmentPort {
    private final IndustryFulfillmentService service;
    public IndustryFulfillmentPortImpl(IndustryFulfillmentService service) { this.service=service; }
    @Override public Receipt verifyPickup(VerifyPickup command,String key) { return service.verifyPickup(command,key); }
    @Override public Receipt query(Reference reference,String actor,String key) { return service.query(reference,actor,key); }
}
