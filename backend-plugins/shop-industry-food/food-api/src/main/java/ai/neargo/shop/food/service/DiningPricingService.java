package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningQuoteReq;
import ai.neargo.shop.food.dto.DiningQuoteConfirmReq;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;

/** Restaurant authorization and source routing; all money calculation remains in shared trade. */
public interface DiningPricingService {
    QuoteVO staffQuote(String entity, String store, String actor, DiningQuoteReq request, String key);
    QuoteVO customerQuote(String store, CartAccess access, DiningQuoteReq request, String key);
    QuoteVO staffGet(String entity, String store, String actor, String cart, long version, String quote);
    QuoteVO customerGet(CartAccess access, String cart, long version, String quote);
    QuoteVO staffConfirm(String entity, String store, String actor, String quote, DiningQuoteConfirmReq request);
    QuoteVO customerConfirm(CartAccess access, String quote, DiningQuoteConfirmReq request);
}
