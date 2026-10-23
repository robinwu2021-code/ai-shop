package ai.neargo.shop.trade.order.service.create;

import java.time.Instant;

/** Constructed only by the owner from locked durable authorization, never a public request body. */
public record ExpectedCheckoutQuote(
        String operationNo,
        String quoteNo,
        long quoteRevision,
        String quoteHash,
        String confirmationNo,
        String scopeHash,
        Instant expiresAt) {}
