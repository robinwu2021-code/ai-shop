package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort;
import ai.neargo.shop.trade.service.IndustryOrderAmendmentService;

import org.springframework.stereotype.Component;

@Component
public class IndustryOrderAmendmentPortImpl implements IndustryOrderAmendmentPort {
    private final IndustryOrderAmendmentService service;

    public IndustryOrderAmendmentPortImpl(IndustryOrderAmendmentService service) {
        this.service = service;
    }

    public Receipt amend(Command c, String key) {
        return service.amend(c, key);
    }

    public Receipt query(Reference r, String actor, String key) {
        return service.query(r, actor, key);
    }

    public Receipt adjustFee(FeeScopeAdjustment c, String key) {
        return service.adjustFee(c, key);
    }
}
