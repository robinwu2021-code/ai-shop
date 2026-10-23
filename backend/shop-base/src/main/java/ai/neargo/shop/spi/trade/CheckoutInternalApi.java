package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Versioned B owner transport. Consumer purchase authorization has no machine endpoint. */
public interface CheckoutInternalApi {
    String BASE = "/internal/trade/checkout/";

    /**
     * Prepare on behalf of a currently authorized merchant; the referenced customer cannot
     * authorize purchase.
     */
    @PostExchange(BASE + "prepare")
    Result<CheckoutPort.CheckoutQuote> prepare(
            @RequestBody Scoped<CheckoutPort.PrepareCheckoutReq> request);

    /**
     * Read through current merchant scope without exposing a machine consumer-confirmation route.
     */
    @PostExchange(BASE + "readQuote")
    Result<CheckoutPort.CheckoutQuote> readQuote(
            @RequestBody Scoped<CheckoutPort.OperationRef> request);

    /** Recover original durable owner state after a lost response or caller restart. */
    @PostExchange(BASE + "findOperation")
    Result<CheckoutPort.CheckoutResult> findOperation(
            @RequestBody Scoped<CheckoutPort.OperationRef> request);

    /** Read verified historical source facts after current merchant permission checks. */
    @PostExchange(BASE + "source")
    Result<CheckoutPort.CheckoutSourceFacts> source(
            @RequestBody Scoped<CheckoutPort.SourceLookup> request);

    /**
     * Read original payment facts through the current merchant scope without consumer parameters.
     */
    @PostExchange(BASE + "findPayment")
    Result<CheckoutPort.PaymentAttemptView> findPayment(
            @RequestBody Scoped<CheckoutPort.PaymentLookup> request);

    /**
     * Bind the versioned transport envelope to the real delegated merchant namespace before owner
     * checks.
     */
    record Scoped<T>(
            int protocolVersion,
            DelegatedIdentity identity,
            CheckoutPort.CheckoutScope scope,
            T request) {
        /** Reject mismatched actor envelopes before any delegated execution. */
        public Scoped {
            if (protocolVersion != 1 || identity == null || scope == null || request == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if (!"MERCHANT".equals(identity.realm())
                    || !("MCH".equals(identity.subjectKind())
                            || "USR".equals(identity.subjectKind()))
                    || !scope.tenantNo().equals(identity.tenantNo())
                    || !scope.actorNo().equals(identity.actorNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
        }

        /**
         * Reject unknown fields locally so unit or authority fields cannot be silently discarded.
         */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        @Override
        public String toString() {
            return "Scoped[protocolVersion=" + protocolVersion + ", request=REDACTED]";
        }
    }

    /** Keep internal responses bare and versioned instead of applying the public API wrapper. */
    record Result<T>(int protocolVersion, T result) {}
}
