package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.SourceDecision;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import java.time.Instant;

/** Internal durable quote journal, not an asset quote source, debit authorization or checkout API. */
public interface PricingFundingSourceService {
    String SOURCE_TYPE = "PRICING_QUOTE_USE";
    record View(SourceRef source, String quoteNo, String status, Instant expiresAt, String snapshotHash) { }
    View stage(Context context, Credentials credentials, String quoteNo, String expectedPreparationHash);
    View get(Context context, String sourceNo);
    View cancel(Context context, String sourceNo, String reason);
    SourceDecision pendingDecision(EntitlementContext context, SourceRef source);
}
