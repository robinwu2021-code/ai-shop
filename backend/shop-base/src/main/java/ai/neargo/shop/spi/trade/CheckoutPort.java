package ai.neargo.shop.spi.trade;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Shared ordinary checkout owner contract. Consumer writes require the current HOST session. */
public interface CheckoutPort {
    /**
     * Resolve a strict customer reference and freeze the existing writer price without purchase
     * effects.
     */
    CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request);

    /**
     * Read a frozen quote only after the current merchant permission and store scope are checked.
     */
    CheckoutQuote readQuote(CheckoutScope scope, OperationRef operation);

    /**
     * Authorize exactly this quote using the real current consumer session; references cannot
     * consent.
     */
    BuyerConfirmation confirm(ConfirmCheckoutReq request);

    /**
     * Consume the original confirmation and stable request key through the ordinary order writer.
     */
    CheckoutResult place(PlaceCheckoutReq request);

    /** Recover durable operation state independently of the short-lived idempotency cache. */
    CheckoutResult findOperation(CheckoutScope scope, OperationRef operation);

    /**
     * Expose verified historical owner facts with a serialized observation revision for later
     * binding.
     */
    CheckoutSourceFacts source(CheckoutScope scope, SourceLookup request);

    /** Initiate or recover the permanent original payment request for the real current buyer. */
    PaymentAttemptView initiatePayment(InitiatePaymentReq request);

    /**
     * Read original payment facts under current merchant scope without exposing payment parameters.
     */
    PaymentAttemptView findPayment(CheckoutScope scope, PaymentLookup request);

    /**
     * A real current consumer chooses a channel; amounts and owner scope come from the placed
     * order.
     */
    record InitiatePaymentReq(OperationRef operation, String requestKey, String channel) {
        /** Require a bounded original key without accepting client-supplied money or owner scope. */
        public InitiatePaymentReq {
            if (operation == null) {
                throw invalid();
            }
            reference(requestKey, 128);
            if (channel != null) {
                reference(channel, 32);
            }
        }

        /** Reject caller-supplied authority fields at the new payment boundary. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "InitiatePaymentReq[operation=" + operation + ", requestKey=REDACTED]";
        }
    }

    /**
     * Locate one original request by business operation number or by the current actor's original
     * key.
     */
    record PaymentLookup(OperationRef operation, String paymentOperationNo, String requestKey) {
        /** Use exactly one lookup identity to preserve the current actor namespace. */
        public PaymentLookup {
            if (operation == null || (paymentOperationNo == null) == (requestKey == null)) {
                throw invalid();
            }
            if (paymentOperationNo != null) {
                reference(paymentOperationNo, 64);
            }
            if (requestKey != null) {
                reference(requestKey, 128);
            }
        }

        /** Reject caller-supplied authority fields at the new payment boundary. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "PaymentLookup[operation=" + operation + ", requestKey=REDACTED]";
        }
    }

    /**
     * A payment observation, with current-buyer-only parameters and the real financial receipt
     * identity.
     */
    record PaymentAttemptView(
            OperationRef operation,
            String paymentOperationNo,
            String phase,
            String paymentNo,
            String outTradeNo,
            String channel,
            String currency,
            long amountMinor,
            Map<String, String> payParams,
            String receiptNo,
            String reasonCode,
            Instant observedAt) {
        /** Copy returned parameters so callers cannot mutate another observation. */
        public PaymentAttemptView {
            payParams = Map.copyOf(payParams);
        }

        @Override
        public String toString() {
            return "PaymentAttemptView[operation="
                    + operation
                    + ", phase="
                    + phase
                    + ", payParams=REDACTED]";
        }
    }

    /**
     * Explicit merchant scope is verified against the current actor; it does not grant permission.
     */
    record CheckoutScope(String tenantNo, String entityNo, String storeNo, String actorNo) {
        /**
         * Reject unknown input locally instead of accepting an implicit unit or authority field.
         */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        /** Validate the canonical request before owner authorization or persistence. */
        public CheckoutScope {
            if (!"MAIN".equals(tenantNo)) {
                throw invalid();
            }
            reference(entityNo, 32);
            reference(storeNo, 32);
            reference(actorNo, 64);
        }
    }

    /** Stable caller-owned operation identity, restricted to the current supported consumer. */
    record OperationRef(String caller, String operationNo) {
        /**
         * Reject unknown input locally instead of accepting an implicit unit or authority field.
         */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        /** Validate the canonical request before owner authorization or persistence. */
        public OperationRef {
            if (!"BEAUTY".equals(caller)) {
                throw invalid();
            }
            reference(operationNo, 64);
        }
    }

    /**
     * Strict buyer reference and canonical selections; diagnostics redact all reusable credentials.
     */
    record PrepareCheckoutReq(
            OperationRef operation,
            String requestKey,
            String customerRef,
            long scopeRevision,
            String scopeHash,
            List<CheckoutLineReq> lines,
            String payMode,
            String couponNo,
            Map<String, String> activityChoices,
            String remark) {
        /**
         * Reject unknown input locally instead of accepting an implicit unit or authority field.
         */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        /** Validate the canonical request before owner authorization or persistence. */
        public PrepareCheckoutReq {
            if (operation == null || scopeRevision < 1) {
                throw invalid();
            }
            reference(requestKey, 128);
            reference(customerRef, 4096);
            hash(scopeHash);
            lines = immutable(lines);
            if (lines.isEmpty() || lines.size() > 20) {
                throw invalid();
            }
            var refs = new HashSet<String>();
            var skus = new HashSet<String>();
            for (var line : lines) {
                if (!refs.add(line.clientLineRef()) || !skus.add(line.skuNo())) {
                    throw invalid();
                }
            }
            if (!"ONLINE".equals(payMode) && !"OFFLINE".equals(payMode)) {
                throw invalid();
            }
            if (couponNo != null) {
                reference(couponNo, 64);
            }
            if (activityChoices == null) {
                throw invalid();
            }
            activityChoices = Map.copyOf(activityChoices);
            activityChoices.forEach(
                    (key, value) -> {
                        reference(key, 64);
                        reference(value, 64);
                    });
            if (remark != null && remark.length() > 500) {
                throw invalid();
            }
        }

        @Override
        public String toString() {
            return "PrepareCheckoutReq[operation="
                    + operation
                    + ", lines="
                    + lines.size()
                    + ", customerRef=REDACTED, requestKey=REDACTED]";
        }
    }

    /** An ordinary service count has no unit conversion or implicit fractional representation. */
    record CheckoutLineReq(
            String clientLineRef,
            String skuNo,
            @JsonDeserialize(using = CountDeserializer.class) int quantity) {
        /**
         * Reject unknown input locally instead of accepting an implicit unit or authority field.
         */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        /** Validate the canonical request before owner authorization or persistence. */
        public CheckoutLineReq {
            reference(clientLineRef, 64);
            reference(skuNo, 64);
            if (quantity < 1 || quantity > 999) {
                throw invalid();
            }
        }
    }

    /**
     * Immutable display and economic facts that the buyer must explicitly confirm before purchase.
     */
    record CheckoutQuote(
            OperationRef operation,
            String quoteNo,
            long quoteRevision,
            String quoteHash,
            Instant expiresAt,
            String buyerNo,
            String entityNo,
            String storeNo,
            String currency,
            List<QuoteLine> lines,
            long goodsMinor,
            long discountMinor,
            long dueMinor,
            String payMode,
            String fulfillment,
            String fulfillmentAuthority,
            List<PromotionOption> promotions,
            List<DiscountFact> discounts,
            String phase,
            long operationRevision) {
        /** Freeze result collections so callers cannot change owner evidence. */
        public CheckoutQuote {
            lines = immutable(lines);
            promotions = immutable(promotions);
            discounts = immutable(discounts);
        }
    }

    /**
     * The actual writer price and paid share are sealed together with stable client line identity.
     */
    record QuoteLine(
            String clientLineRef,
            String skuNo,
            String goodsNo,
            int quantity,
            String uom,
            long unitPriceMinor,
            long grossMinor,
            long discountMinor,
            long dueMinor,
            String productVersionHash) {}

    /** Owner-provided selection evidence never grants a benefit by itself. */
    record PromotionOption(
            String promotionNo, String kind, String title, boolean eligible, String reasonCode) {}

    /** Retains discount provenance and funding so equal totals cannot hide a changed allocation. */
    record DiscountFact(
            String promotionNo,
            String kind,
            long discountMinor,
            String fundingKind,
            String ownerVersion) {}

    /** Binds consumer consent to one immutable quote and one permanent confirmation key. */
    record ConfirmCheckoutReq(
            OperationRef operation,
            String quoteNo,
            long quoteRevision,
            String quoteHash,
            String requestKey) {
        /**
         * Reject unknown input locally instead of accepting an implicit unit or authority field.
         */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        /** Validate the canonical request before owner authorization or persistence. */
        public ConfirmCheckoutReq {
            if (operation == null || quoteRevision != 1) {
                throw invalid();
            }
            reference(quoteNo, 40);
            reference(requestKey, 128);
            hash(quoteHash);
        }

        @Override
        public String toString() {
            return "ConfirmCheckoutReq[operation="
                    + operation
                    + ", quoteNo="
                    + quoteNo
                    + ", requestKey=REDACTED]";
        }
    }

    /** Durable authorization metadata; the identifier alone is not a consumer credential. */
    record BuyerConfirmation(
            String confirmationNo,
            OperationRef operation,
            String quoteNo,
            long quoteRevision,
            String quoteHash,
            Instant expiresAt,
            String status) {}

    /**
     * Names the original confirmation and first placement key; it accepts no caller price or buyer.
     */
    record PlaceCheckoutReq(OperationRef operation, String confirmationNo, String requestKey) {
        /**
         * Reject unknown input locally instead of accepting an implicit unit or authority field.
         */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        /** Validate the canonical request before owner authorization or persistence. */
        public PlaceCheckoutReq {
            if (operation == null) {
                throw invalid();
            }
            reference(confirmationNo, 40);
            reference(requestKey, 128);
        }

        @Override
        public String toString() {
            return "PlaceCheckoutReq[operation="
                    + operation
                    + ", confirmationNo="
                    + confirmationNo
                    + ", requestKey=REDACTED]";
        }
    }

    /** Durable checkout recovery state; PLACING represents the original creation intent only. */
    record CheckoutResult(
            OperationRef operation,
            String phase,
            long revision,
            String orderNo,
            String subOrderNo,
            String sourceHash,
            String reasonCode,
            Instant observedAt) {}

    /** Looks up the original operation and optionally asserts the exact existing child order. */
    record SourceLookup(OperationRef operation, String subOrderNo) {
        /**
         * Reject unknown input locally instead of accepting an implicit unit or authority field.
         */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        /** Validate the canonical request before owner authorization or persistence. */
        public SourceLookup {
            if (operation == null) {
                throw invalid();
            }
            if (subOrderNo != null) {
                reference(subOrderNo, 64);
            }
        }
    }

    /**
     * UNKNOWN carries only the last verified revision/hash pair and no consumable lines or money.
     */
    record CheckoutSourceFacts(
            OperationRef operation,
            String orderNo,
            String subOrderNo,
            String buyerNo,
            String entityNo,
            String storeNo,
            long sourceRevision,
            String sourceHash,
            String orderStatus,
            String currency,
            long dueMinor,
            Long receivedMinor,
            Long outstandingMinor,
            String financialState,
            List<SourceLine> lines,
            List<PaymentFact> payments,
            String executionState,
            Instant observedAt) {
        /** Freeze result collections so callers cannot change owner evidence. */
        public CheckoutSourceFacts {
            lines = immutable(lines);
            payments = immutable(payments);
        }
    }

    /**
     * Historical COUNT quantity and immutable order item identity remain stable across refund
     * observations.
     */
    record SourceLine(
            String sourceLineNo,
            String clientLineRef,
            String goodsNo,
            String skuNo,
            int orderedQuantity,
            int refundableQuantity,
            String uom,
            long dueMinor) {}

    /** Read-only original receipt evidence; this contract does not initiate or settle payments. */
    record PaymentFact(
            String paymentOperationNo,
            String paymentNo,
            String outTradeNo,
            String channel,
            String tradeNo,
            String status,
            long amountMinor,
            String currency,
            String receiptNo,
            Instant observedAt) {}

    private static void reference(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.equals(value.strip())) {
            throw invalid();
        }
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw invalid();
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.stream().anyMatch(v -> v == null)) {
            throw invalid();
        }
        return List.copyOf(values);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid checkout contract");
    }

    /** Prevent JSON string/decimal coercion from changing the explicitly counted sales quantity. */
    /**
     * Local JSON integer parsing prevents Jackson coercion from changing the service count
     * contract.
     */
    final class CountDeserializer extends StdDeserializer<Integer> {
        /** Register an integer-only decoder without changing other API numeric coercion. */
        public CountDeserializer() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                throw invalid();
            }
            return parser.getIntValue();
        }
    }
}
