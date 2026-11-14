package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;
import ai.neargo.shop.spi.trade.OfflineReceiptPort.MinorAmountDeserializer;
import ai.neargo.shop.spi.trade.OfflineReceiptPort.OfflineReceiptReq;
import ai.neargo.shop.trade.service.OfflineReceiptService;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/** Current merchant cash receipt endpoints, with server-derived actual store and actor scope. */
@Profile("api")
@RestController
@Validated
public class BizOfflineReceiptController {
    private final OfflineReceiptService receipts;

    /** Use the trade owner without accepting a caller-supplied financial instruction. */
    public BizOfflineReceiptController(OfflineReceiptService receipts) {
        this.receipts = receipts;
    }

    /** Record an already received full offline amount; no third-party charge is initiated. */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CASH_RECEIVE + "')")
    @PostMapping("/biz/checkout/{operationNo}/offline-receipts")
    public PaymentAttemptView confirm(
            @PathVariable String operationNo,
            @RequestBody @Valid ReceiptBody body,
            @RequestHeader("Idempotency-Key") @NotBlank String key) {
        OfflineReceiptReq request;
        try {
            request =
                    new OfflineReceiptReq(
                            new OperationRef("BEAUTY", operationNo),
                            key,
                            body.method(),
                            body.amountMinor(),
                            body.currency(),
                            body.evidenceRef(),
                            body.receivedAt(),
                            body.note());
        } catch (IllegalArgumentException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return receipts.confirm(scope(), request);
    }

    /** Read existing receipt facts by business identity without exposing payment parameters. */
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @GetMapping("/biz/checkout/{operationNo}/offline-receipts/{paymentOperationNo}")
    public PaymentAttemptView find(
            @PathVariable String operationNo, @PathVariable String paymentOperationNo) {
        return receipts.find(
                scope(),
                new PaymentLookup(
                        new OperationRef("BEAUTY", operationNo), paymentOperationNo, null));
    }

    /** Recover only this current merchant actor's original key, carried outside URL access logs. */
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @PostMapping("/biz/checkout/{operationNo}/offline-receipts/lookup")
    public PaymentAttemptView lookup(
            @PathVariable String operationNo,
            @RequestHeader("Idempotency-Key") @NotBlank String key) {
        return receipts.find(
                scope(), new PaymentLookup(new OperationRef("BEAUTY", operationNo), null, key));
    }

    private CheckoutScope scope() {
        var context = BizContext.current();
        return new CheckoutScope(
                "MAIN",
                context.requireMerchantNo(),
                BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo());
    }

    /** Exact full-receipt body; identities, scope and ledger instructions are never accepted. */
    public record ReceiptBody(
            String method,
            @JsonProperty(required = true) @JsonDeserialize(using = MinorAmountDeserializer.class)
                    long amountMinor,
            String currency,
            String evidenceRef,
            Instant receivedAt,
            String note) {
        /** Reject arbitrary extra amount or authority fields at the HTTP boundary. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        @Override
        public String toString() {
            return "ReceiptBody[redacted]";
        }
    }
}
