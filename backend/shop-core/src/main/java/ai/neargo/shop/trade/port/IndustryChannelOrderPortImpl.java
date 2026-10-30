package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.trade.service.IndustryChannelOrderService;

import org.springframework.stereotype.Component;

/**
 * The original internal port delegates to core; it does not accept caller prices or cash
 * acknowledgement.
 */
@Component
public final class IndustryChannelOrderPortImpl implements IndustryChannelOrderPort {
    private final IndustryChannelOrderService service;

    public IndustryChannelOrderPortImpl(IndustryChannelOrderService service) {
        this.service = service;
    }

    @Override
    public Preparation prepare(Reference reference) {
        return service.prepare(reference);
    }

    @Override
    public Preparation get(Reference reference) {
        return service.get(reference);
    }

    @Override
    public Acceptance confirm(Reference reference) {
        return service.confirm(reference);
    }

    @Override
    public Cancellation cancel(Reference reference) {
        return service.cancel(reference);
    }
}
