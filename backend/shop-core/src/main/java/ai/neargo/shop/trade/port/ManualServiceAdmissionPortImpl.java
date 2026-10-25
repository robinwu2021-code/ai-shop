package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.trade.service.ManualServiceAdmissionService;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Shared owner adapter keeps merchant declaration authority inside Core. */
@Component
@Primary
public class ManualServiceAdmissionPortImpl implements ManualServiceAdmissionPort {
    private final ManualServiceAdmissionService service;

    public ManualServiceAdmissionPortImpl(ManualServiceAdmissionService service) {
        this.service = service;
    }

    @Override
    public CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request) {
        return service.prepare(scope, request);
    }

    @Override
    public ManualAdmissionView confirm(CheckoutScope scope, ConfirmManualReceipt request) {
        return service.confirm(scope, request);
    }

    @Override
    public ManualAdmissionView query(CheckoutScope scope, ManualLookup request) {
        return service.query(scope, request);
    }

    @Override
    public ManualSourceFacts source(CheckoutScope scope, SourceLookup request) {
        return service.source(scope, request);
    }
}
