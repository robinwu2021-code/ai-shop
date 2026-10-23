package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.QuoteVO;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.SaveReq;

/** Internal immutable evidence store. Transport authorization and actual source admission precede this service. */
public interface PricingQuoteService {
    QuoteVO save(Context context, SaveReq request, String idempotencyKey);
    QuoteVO get(Context context, String quoteNo);
    QuoteVO retain(Context context, String quoteNo, String originalReference);
    QuoteVO supersede(Context context, String quoteNo, String replacementQuoteNo);
}
