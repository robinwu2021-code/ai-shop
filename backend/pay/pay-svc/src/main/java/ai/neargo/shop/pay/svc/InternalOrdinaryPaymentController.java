package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.port.OrdinaryPaymentPortImpl;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.OrdinaryPaymentInitiationService;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated ordinary PAY ledger operations; core-owned identity is required for initiation; no cancellation or refund API. */
@RestController
public class InternalOrdinaryPaymentController {
    private final OrdinaryPaymentPort payments;
    private final String token;
    private final OrdinaryPaymentInitiationService initiation;

    public InternalOrdinaryPaymentController(PaymentLedgerService ledger, PayQueryPort channel,
            OrdinaryPaymentInitiationService initiation,
            @Value("${shop.services.internal-token:}") String token) {
        this.payments = new OrdinaryPaymentPortImpl(ledger, channel);
        this.token = token;
        this.initiation = initiation;
    }

    @PostMapping(PayInternalPaths.ORDINARY_PAYMENT_INITIATE)
    public ResponseEntity<?> initiate(@RequestBody PayOrdinaryPaymentRequests.Initiate request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> initiation.initiate(request.payment(), request.payer()));
    }

    @PostMapping(PayInternalPaths.ORDINARY_PAYMENT_OPEN)
    public ResponseEntity<?> open(@RequestBody SettlePort.PaymentOpen request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> payments.open(request));
    }

    @PostMapping(PayInternalPaths.ORDINARY_PAYMENT_QUERY)
    public ResponseEntity<?> query(@RequestBody OrdinaryPaymentPort.Reference request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> payments.query(request));
    }

    @PostMapping(PayInternalPaths.ORDINARY_PAYMENT_REFRESH)
    public ResponseEntity<?> refresh(@RequestBody OrdinaryPaymentPort.Reference request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> payments.refresh(request));
    }

    @PostMapping(PayInternalPaths.ORDINARY_PAYMENT_SUCCESS)
    public ResponseEntity<?> success(@RequestBody OrdinaryPaymentPort.OrderReference request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> payments.success(request));
    }

    @PostMapping(PayInternalPaths.ORDINARY_PAYMENT_CHANNEL_QUERY)
    public ResponseEntity<?> channel(@RequestBody OrdinaryPaymentPort.Reference request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> payments.queryChannel(request));
    }
}
