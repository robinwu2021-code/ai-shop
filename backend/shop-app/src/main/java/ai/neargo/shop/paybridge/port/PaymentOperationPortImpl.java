package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.paybridge.service.PaymentDispatchService;
import ai.neargo.shop.spi.settle.PaymentOperationPort;

import org.springframework.stereotype.Component;

/** Single host binding; all behavior lives in the lock-free dispatcher and real pay owner. */
@Component
public class PaymentOperationPortImpl implements PaymentOperationPort {
    private final PaymentDispatchService service;

    /** Keep trusted owner calls behind the lock-free dispatch orchestrator. */
    public PaymentOperationPortImpl(PaymentDispatchService service) {
        this.service = service;
    }

    @Override
    public PaymentOperationView prepare(PaymentOperationReq request) {
        return service.prepare(request);
    }

    @Override
    public PaymentOperationView initiate(String no) {
        return service.initiate(no);
    }

    @Override
    public PaymentOperationView query(String no) {
        return service.query(no);
    }

    @Override
    public PaymentOperationView confirmOffline(OfflinePaymentReceiptReq request) {
        return service.confirmOffline(request);
    }
}
