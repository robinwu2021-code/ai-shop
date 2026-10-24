package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.port.OrdinaryRefundPortImpl;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Service-authenticated cash execution; the trusted core source owner retains approval and reversal. */
@RestController
public class InternalOrdinaryRefundController {
    private final OrdinaryRefundPort refunds;
    private final String token;

    public InternalOrdinaryRefundController(PaymentLedgerService ledger, RefundExecutionService refunds,
            @Value("${shop.services.internal-token:}") String token) {
        this.refunds = new OrdinaryRefundPortImpl(ledger, refunds);
        this.token = token;
    }

    @PostMapping(PayInternalPaths.ORDINARY_REFUND_REQUEST)
    public ResponseEntity<?> request(@RequestBody OrdinaryRefundPort.Execution request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> refunds.request(request));
    }

    @PostMapping(PayInternalPaths.ORDINARY_REFUND_QUERY)
    public ResponseEntity<?> query(@RequestBody OrdinaryRefundPort.Execution request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> refunds.query(request));
    }
}
