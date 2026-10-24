package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import java.math.BigDecimal;

/** Full-cart projection preserves original invoice identity; it invents no tax or promotion classification. */
public final class DiningPricingSourcePolicy {
    private DiningPricingSourcePolicy() { }
    public static void source(PricingSourceRef source) {
        if (source == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (!"FOOD".equals(source.sourceSystem()) || !"CART".equals(source.sourceType())
                || source.scopeNo() != null || source.scopeVersion() != null) {
            throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        }
        CartInputPolicy.key(source.sourceNo()); CartInputPolicy.version(source.sourceVersion());
    }
    public static Snapshot project(DiningPriceFacts result, Scope expectedScope, PricingSourceRef source,
            String subject) {
        source(source);
        DiningPriceFactsPolicy.validate(result, expectedScope == null ? null : expectedScope.entityNo(),
                expectedScope == null ? null : expectedScope.storeNo(), source.sourceNo(),
                source.sourceVersion(), subject);
        var p = result.payload();
        var scope = new Scope("MAIN", p.entityNo(), p.cart().storeNo());
        var composition = p.units().stream().map(unit -> new Component(Kind.valueOf(unit.kind()),
                new PricingUnit(unit.sourceLineNo(), unit.componentRef(), unit.goodsNo(), unit.skuNo(),
                        unit.priceRevision(), unit.sourceLineNo(), BigDecimal.ZERO, unit.quantity(),
                        new MoneyMinor(p.currency(), unit.unitPriceMinor()),
                        new MoneyMinor(p.currency(), unit.amountMinor())))).toList();
        var snapshot = PricingSourceFacts.freeze(scope, subject, source, p.cart().orderSource(), result.sourceHash(),
                composition);
        if (snapshot.prices().baseTotal().minor() != p.amountMinor()) {
            throw new IllegalArgumentException("Restaurant source amount must conserve shared price facts");
        }
        return snapshot;
    }
}
