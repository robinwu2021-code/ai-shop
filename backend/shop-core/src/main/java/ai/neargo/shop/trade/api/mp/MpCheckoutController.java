package ai.neargo.shop.trade.api.mp;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort.BuyerConfirmation;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.ConfirmCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.InitiatePaymentReq;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;
import ai.neargo.shop.spi.trade.CheckoutPort.PlaceCheckoutReq;
import ai.neargo.shop.trade.service.CheckoutService;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The current consumer authorizes exactly the durable quote they read. */
@Profile("api")
@RestController
@Validated
public class MpCheckoutController {
    private final CheckoutService checkout;

    /** Keep consumer reads on the local owner so clients cannot fabricate merchant scope. */
    public MpCheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    /** Locate the quote by the authenticated buyer and stable operation, without B credentials. */
    @GetMapping("/mp/checkout/{operationNo}")
    public CheckoutQuote read(@PathVariable String operationNo) {
        return checkout.readQuoteForCurrentBuyer(new OperationRef("BEAUTY", operationNo));
    }

    /** Persist consent for the exact displayed economics using the current consumer session. */
    @PostMapping("/mp/checkout/{operationNo}/confirmations")
    public BuyerConfirmation confirm(
            @PathVariable String operationNo,
            @RequestBody @Valid ConfirmBody body,
            @RequestHeader("Idempotency-Key") @NotBlank String key) {
        return checkout.confirm(
                new ConfirmCheckoutReq(
                        new OperationRef("BEAUTY", operationNo),
                        body.quoteNo(),
                        body.quoteRevision(),
                        body.quoteHash(),
                        key));
    }

    /** Recover or consume the original consent with a permanent client request key. */
    @PostMapping("/mp/checkout/{operationNo}/orders")
    public CheckoutResult place(
            @PathVariable String operationNo,
            @RequestBody @Valid PlaceBody body,
            @RequestHeader("Idempotency-Key") @NotBlank String key) {
        return checkout.place(
                new PlaceCheckoutReq(
                        new OperationRef("BEAUTY", operationNo), body.confirmationNo(), key));
    }

    /** Start only the current buyer's original payment; no amount or owner fields are accepted. */
    @PostMapping("/mp/checkout/{operationNo}/payments")
    public PaymentAttemptView initiatePayment(
            @PathVariable String operationNo,
            @RequestBody @Valid PaymentBody body,
            @RequestHeader("Idempotency-Key") @NotBlank String key) {
        return checkout.initiatePayment(
                new InitiatePaymentReq(
                        new OperationRef("BEAUTY", operationNo), key, body.channel()));
    }

    /** Read by public business identity; never put the reusable raw key in an access-log URL. */
    @GetMapping("/mp/checkout/{operationNo}/payments/{paymentOperationNo}")
    public PaymentAttemptView payment(
            @PathVariable String operationNo, @PathVariable String paymentOperationNo) {
        return checkout.findPaymentForCurrentBuyer(
                new PaymentLookup(
                        new OperationRef("BEAUTY", operationNo), paymentOperationNo, null));
    }

    /**
     * Recover a lost response through the current buyer's namespace without initiating another
     * send.
     */
    @PostMapping("/mp/checkout/{operationNo}/payments/lookup")
    public PaymentAttemptView lookupPayment(
            @PathVariable String operationNo,
            @RequestHeader("Idempotency-Key") @NotBlank String key) {
        return checkout.findPaymentForCurrentBuyer(
                new PaymentLookup(new OperationRef("BEAUTY", operationNo), null, key));
    }

    /** The only payment selection the consumer supplies is the requested channel. */
    public record PaymentBody(String channel) {
        /** Reject caller-supplied amounts, identities and internal owner instructions. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** Buyers name immutable quote evidence; they cannot supply price, buyer or actor fields. */
    public record ConfirmBody(
            @NotBlank String quoteNo, long quoteRevision, @NotBlank String quoteHash) {
        /** Reject extra authority or amount fields locally without changing older APIs. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** Purchase refers only to prior consent; the idempotency key belongs to the HTTP header. */
    public record PlaceBody(@NotBlank String confirmationNo) {
        /** Reject extra authority or amount fields locally without changing older APIs. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
