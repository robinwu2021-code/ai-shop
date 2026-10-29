package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.trade.service.CheckoutService;

import org.springframework.stereotype.Component;

/** Cross-domain callers reach the same ordinary checkout owner service. */
@Component
public class CheckoutPortImpl implements CheckoutPort {
    private final CheckoutService service;

    public CheckoutPortImpl(CheckoutService service) {
        this.service = service;
    }

    @Override
    public PaymentAttemptView initiatePayment(InitiatePaymentReq request) {
        return service.initiatePayment(request);
    }

    @Override
    public PaymentAttemptView findPayment(CheckoutScope scope, PaymentLookup request) {
        return service.findPayment(scope, request);
    }

    @Override
    public CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request) {
        return service.prepare(scope, request);
    }

    @Override
    public CheckoutQuote readQuote(CheckoutScope scope, OperationRef operation) {
        return service.readQuote(scope, operation);
    }

    @Override
    public BuyerConfirmation confirm(ConfirmCheckoutReq request) {
        return service.confirm(request);
    }

    @Override
    public CheckoutResult place(PlaceCheckoutReq request) {
        return service.place(request);
    }

    @Override
    public CheckoutResult findOperation(CheckoutScope scope, OperationRef operation) {
        return service.findOperation(scope, operation);
    }

    @Override
    public CheckoutSourceFacts source(CheckoutScope scope, SourceLookup request) {
        return service.source(scope, request);
    }
}
