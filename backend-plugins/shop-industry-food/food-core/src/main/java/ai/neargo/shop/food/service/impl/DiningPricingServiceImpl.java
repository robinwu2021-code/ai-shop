package ai.neargo.shop.food.service.impl;

import java.util.function.Supplier;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningQuoteReq;
import ai.neargo.shop.food.dto.DiningQuoteConfirmReq;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningPricingService;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.Selection;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;

/** No database transaction is held while the core resolves prices, tax or published rules. */
public class DiningPricingServiceImpl implements DiningPricingService {
    private final CheckCartService carts;
    private final Supplier<PricingCheckoutPort> pricing;
    public DiningPricingServiceImpl(CheckCartService carts, PricingCheckoutPort pricing) {
        this(carts, () -> pricing);
    }
    public DiningPricingServiceImpl(CheckCartService carts, Supplier<PricingCheckoutPort> pricing) {
        this.carts = carts; this.pricing = pricing;
    }
    private PricingCheckoutPort pricing() {
        var value = pricing.get();
        if (value == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return value;
    }
    @Override public QuoteVO staffQuote(String entity, String store, String actor, DiningQuoteReq r, String key) {
        request(r); staff(entity, store, actor, r.cartNo(), r.version());
        return pricing().quoteStaff(scope(entity, store), actor, source(r.cartNo(), r.version()),
                r.selection() == null ? Selection.none() : r.selection(), key);
    }
    @Override public QuoteVO customerQuote(String store, CartAccess access, DiningQuoteReq r, String key) {
        request(r); CartInputPolicy.key(store);
        var cart = customer(access, r.cartNo(), r.version());
        if (!store.equals(cart.storeNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        return pricing().quoteCustomer(source(r.cartNo(), r.version()), credentials(access),
                r.selection() == null ? Selection.none() : r.selection(), key);
    }
    @Override public QuoteVO staffGet(String entity, String store, String actor, String cart, long version,
            String quote) {
        staff(entity, store, actor, cart, version);
        return pricing().getStaff(scope(entity, store), actor, source(cart, version), quote);
    }
    @Override public QuoteVO customerGet(CartAccess access, String cart, long version, String quote) {
        customer(access, cart, version);
        return pricing().getCustomer(source(cart, version), credentials(access), quote);
    }
    @Override public QuoteVO staffConfirm(String entity, String store, String actor, String quote,
            DiningQuoteConfirmReq r) {
        confirm(r); staff(entity, store, actor, r.cartNo(), r.version());
        return pricing().revalidateStaff(scope(entity, store), actor, source(r.cartNo(), r.version()), quote,
                r.confirmationDigest());
    }
    @Override public QuoteVO customerConfirm(CartAccess access, String quote, DiningQuoteConfirmReq r) {
        confirm(r); customer(access, r.cartNo(), r.version());
        return pricing().revalidateCustomer(source(r.cartNo(), r.version()), credentials(access), quote,
                r.confirmationDigest());
    }
    private void staff(String entity, String store, String actor, String cart, long version) {
        CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(actor);
        state(carts.getCart(entity, store, cart).cart(), cart, version);
    }
    private CartVO customer(CartAccess access, String cart, long version) {
        if (access == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = carts.customerGetCart(cart, access).cart();
        state(result, cart, version); return result;
    }
    private static void state(CartVO value, String cart, long version) {
        CartInputPolicy.key(cart);
        if (version < 0) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (value == null || !cart.equals(value.cartNo()) || value.version() == null
                || value.version() != version || !"OPEN".equals(value.status())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private static Scope scope(String entity, String store) { return new Scope("MAIN", entity, store); }
    private static PricingSourceRef source(String cart, long version) {
        return new PricingSourceRef("FOOD", "CART", cart, version, null, null);
    }
    private static Credentials credentials(CartAccess access) {
        return new Credentials(access.sessionNo(), access.accessToken(), access.buyerNo());
    }
    private static void request(DiningQuoteReq r) {
        if (r == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        CartInputPolicy.key(r.cartNo());
        if (r.version() < 0) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static void confirm(DiningQuoteConfirmReq r) {
        if (r == null || r.confirmationDigest() == null || !r.confirmationDigest().matches("[0-9a-f]{64}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
