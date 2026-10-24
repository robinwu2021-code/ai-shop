package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Mode;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import java.util.HashSet;

/** Original order admission separates charged additive SKUs from unsupported bundle stock strategies. */
public final class IndustryOrderCompositionFacts {
    private IndustryOrderCompositionFacts() { }
    /** Every addon is a separate physical SKU demand under exactly one immutable MAIN root. */
    public static void require(Snapshot source, Mode mode) {
        PricingSourceFacts.validate(source);
        var roots = new HashSet<String>();
        for (var part : source.composition()) {
            if (part.kind() != Kind.MAIN && (part.kind() != Kind.ADDON || mode != Mode.POSTPAY)) throw unavailable();
            if (part.unit().componentRef() == null || !part.unit().componentRef().matches("[0-9a-f]{64}")) {
                throw unavailable();
            }
            try {
                if (part.unit().quantity().intValueExact() <= 0) throw unavailable();
            } catch (ArithmeticException fraction) { throw unavailable(); }
            if (part.kind() == Kind.MAIN && !roots.add(part.unit().sourceLineNo())) throw unavailable();
        }
        for (var part : source.composition()) {
            if (!roots.contains(part.unit().sourceLineNo())) throw unavailable();
        }
    }
    private static BizException unavailable() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
