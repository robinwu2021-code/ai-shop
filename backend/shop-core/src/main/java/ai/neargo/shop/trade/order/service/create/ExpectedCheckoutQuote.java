package ai.neargo.shop.trade.order.service.create;

import java.time.Instant;

/**
 * Constructed only from locked durable consumer consent and owner operation, never a request body.
 */
public record ExpectedCheckoutQuote(
        String operationNo,
        String quoteNo,
        long quoteRevision,
        String quoteHash,
        String confirmationNo,
        String scopeHash,
        Instant expiresAt,
        String admissionKind) {
    public ExpectedCheckoutQuote(
            String operationNo,
            String quoteNo,
            long quoteRevision,
            String quoteHash,
            String confirmationNo,
            String scopeHash,
            Instant expiresAt) {
        this(
                operationNo,
                quoteNo,
                quoteRevision,
                quoteHash,
                confirmationNo,
                scopeHash,
                expiresAt,
                "LEGACY_FUNDED");
    }
}
