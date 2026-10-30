package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts;
import ai.neargo.shop.food.service.DiningChannelFulfillmentService;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

public final class FoodChannelFulfillmentSourcePortImpl implements ChannelFulfillmentSourcePort {
    private final DiningChannelFulfillmentService owner;

    public FoodChannelFulfillmentSourcePortImpl(DiningChannelFulfillmentService owner) {
        this.owner = owner;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Facts capture(Query query) {
        if (query == null) throw new IllegalArgumentException("Fulfillment source query required");
        var original =
                new DiningChannelFulfillmentFacts.Query(
                        query.entityNo(),
                        query.storeNo(),
                        query.sourceOrderRef(),
                        query.coreOrderRef(),
                        query.sourceFulfillmentRef(),
                        query.sourceRevision());
        var facts = owner.capture(original);
        if (facts == null || !original.equals(facts.query()))
            throw new IllegalArgumentException("Original custody scope mismatch");
        var data =
                new Data(
                        query,
                        Stage.HANDED_OVER,
                        null,
                        null,
                        null,
                        facts.lines().stream()
                                .map(
                                        line ->
                                                new Line(
                                                        line.sourceLineRef(),
                                                        line.componentRef(),
                                                        line.coreItemRef(),
                                                        line.quantity()
                                                                .stripTrailingZeros()
                                                                .toPlainString(),
                                                        line.unit()))
                                .toList(),
                        facts.occurredAt(),
                        facts.sourceReceiptRef());
        return new Facts(
                data,
                facts.observedAt(),
                facts.validUntil(),
                "FOOD:FULFILLMENT:" + PricingSnapshotHash.of(data));
    }
}
