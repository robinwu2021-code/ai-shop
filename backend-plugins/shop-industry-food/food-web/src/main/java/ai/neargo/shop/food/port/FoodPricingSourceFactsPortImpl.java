package ai.neargo.shop.food.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.policy.DiningPricingSourcePolicy;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import java.util.function.Supplier;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceRef;

/** The same adapter binds the core to embedded food or the existing authenticated standalone client. */
public class FoodPricingSourceFactsPortImpl implements PricingSourceFactsPort {
    private final DiningPricingFactsService service;
    private final Supplier<DiningOrderService> orders;
    public FoodPricingSourceFactsPortImpl(DiningPricingFactsService service) { this(service, () -> null); }
    public FoodPricingSourceFactsPortImpl(DiningPricingFactsService service, Supplier<DiningOrderService> orders) {
        this.service = service; this.orders = orders;
    }
    @Override public Snapshot cancellationForStore(Command command, String key, String fulfillment) {
        var authority = orders.get();
        if (authority == null) throw unsupported();
        return authority.authorizeCancellation(command, key, fulfillment);
    }
    @Override public String sourceSystem() { return "FOOD"; }
    @Override public Snapshot staff(Scope scope, PricingSourceRef source) {
        DiningPricingSourcePolicy.source(source);
        if (scope == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var facts = service.staff(scope.entityNo(), scope.storeNo(), source.sourceNo(), source.sourceVersion());
        try { return DiningPricingSourcePolicy.project(facts, scope, source, null); }
        catch (RuntimeException invalid) { throw unsupported(); }
    }
    @Override public Snapshot customer(PricingSourceRef source, Credentials credentials) {
        DiningPricingSourcePolicy.source(source);
        if (credentials == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var facts = service.customer(source.sourceNo(), new CartAccess(credentials.sessionNo(),
                credentials.accessToken(), credentials.buyerNo()), source.sourceVersion());
        try {
            var cart = facts.payload().cart();
            String subject = credentials.sessionNo() != null ? credentials.sessionNo()
                    : cart.sessionNo() != null ? cart.sessionNo() : credentials.buyerNo();
            if (subject == null) throw unsupported();
            return DiningPricingSourcePolicy.project(facts, null, source, subject);
        } catch (RuntimeException invalid) { throw unsupported(); }
    }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
