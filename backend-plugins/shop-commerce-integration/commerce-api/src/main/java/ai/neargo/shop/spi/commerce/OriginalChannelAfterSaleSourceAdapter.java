package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.policy.ChannelAfterSalePolicy;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import java.time.Clock;
import java.util.function.Function;

/**
 * The real Core owner supplies channel decisions for either industry without a FOOD-menu or buyer
 * fallback.
 */
public final class OriginalChannelAfterSaleSourceAdapter implements ChannelAfterSaleSourcePort {
    private final ChannelSourceValidationPort.Owner owner;
    private final Function<IndustryChannelAfterSalePort.Query, IndustryChannelAfterSalePort.Facts>
            original;
    private final Clock clock;

    public OriginalChannelAfterSaleSourceAdapter(
            ChannelSourceValidationPort.Owner owner,
            Function<IndustryChannelAfterSalePort.Query, IndustryChannelAfterSalePort.Facts>
                    original,
            Clock clock) {
        this.owner = owner;
        this.original = original;
        this.clock = clock;
    }

    @Override
    public ChannelSourceValidationPort.Owner owner() {
        return owner;
    }

    @Override
    public Facts capture(Query query) {
        if (query == null) throw new IllegalArgumentException("Missing original after-sale query");
        var originalQuery =
                new IndustryChannelAfterSalePort.Query(
                        new Scope("MAIN", query.entityNo(), query.storeNo()),
                        owner.name(),
                        query.coreOrderNo(),
                        query.externalCaseRef(),
                        query.sourceDecisionRef(),
                        query.sourceRevision());
        var facts = original.apply(originalQuery);
        if (facts == null
                || !originalQuery.equals(facts.query())
                || !owner.name().equals(facts.decision().original().sourceSystem()))
            throw new IllegalStateException("Missing original channel after-sale decision");
        var result =
                new Facts(
                        query,
                        facts.decision(),
                        facts.revisionNo(),
                        facts.observedAt(),
                        facts.validUntil(),
                        facts.verificationReceiptRef(),
                        facts.fundsStatus(),
                        facts.inventoryStatus());
        ChannelAfterSalePolicy.source(query, result, clock);
        return result;
    }
}
