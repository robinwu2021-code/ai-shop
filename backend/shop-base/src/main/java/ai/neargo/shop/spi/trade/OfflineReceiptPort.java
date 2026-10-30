package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.time.Instant;

/**
 * A current authorized merchant declares actual external receipt against original consumer consent.
 */
public interface OfflineReceiptPort {
    /** Record exactly the original full due, preserving its permanent request identity. */
    PaymentAttemptView confirm(CheckoutScope scope, OfflineReceiptReq request);

    /** Read existing store-authorized receipt facts without initiating collection. */
    PaymentAttemptView find(CheckoutScope scope, PaymentLookup request);

    /**
     * Public receipt facts contain no financial instruction, cashier identity or attachment owner
     * assertion.
     */
    record OfflineReceiptReq(
            OperationRef operation,
            String requestKey,
            String method,
            @JsonProperty(required = true) @JsonDeserialize(using = MinorAmountDeserializer.class)
                    long amountMinor,
            String currency,
            String evidenceRef,
            Instant receivedAt,
            String note) {
        /** Accept only a complete declaration of the original full external receipt. */
        public OfflineReceiptReq {
            if (operation == null
                    || !("CASH".equals(method) || "BANK_TRANSFER".equals(method))
                    || amountMinor < 0
                    || !"CNY".equals(currency)
                    || receivedAt == null
                    || !receivedAt.equals(Instant.ofEpochMilli(receivedAt.toEpochMilli()))) {
                throw invalid();
            }
            bounded(requestKey, 128);
            if ("CASH".equals(method)) {
                bounded(note, 500);
            } else if (note != null) {
                bounded(note, 500);
            }
            if ("BANK_TRANSFER".equals(method) || evidenceRef != null) {
                bounded(evidenceRef, 255);
                if (evidenceRef.contains(":")
                        || evidenceRef.contains("..")
                        || evidenceRef.startsWith("/")) {
                    throw invalid();
                }
            }
        }

        /** Reject authority fields and caller-supplied owner instructions. */
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalid();
        }

        @Override
        public String toString() {
            return "OfflineReceiptReq[redacted]";
        }
    }

    /** Monetary minor units are JSON integers, without numeric-string or fractional coercion. */
    final class MinorAmountDeserializer extends StdDeserializer<Long> {
        /** Register only on the new receipt amount; older API coercion remains unchanged. */
        public MinorAmountDeserializer() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                throw invalid();
            }
            return parser.getLongValue();
        }

        @Override
        public Long getNullValue(DeserializationContext context) {
            throw invalid();
        }
    }

    private static void bounded(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.equals(value.strip())) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid offline receipt contract");
    }
}
