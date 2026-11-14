package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourceFacts;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import java.time.Clock;
import java.util.function.Function;

/**
 * Original pickup/handover receipts only; no carrier package, delivered inference, or cumulative
 * progress conversion.
 */
public class OriginalRetailChannelFulfillmentSourceAdapter implements ChannelFulfillmentSourcePort {
    private final Function<
                    IndustryChannelFulfillmentSourcePort.Query,
                    IndustryChannelFulfillmentSourcePort.Facts>
            source;
    private final Clock clock;

    public OriginalRetailChannelFulfillmentSourceAdapter(
            Function<
                            IndustryChannelFulfillmentSourcePort.Query,
                            IndustryChannelFulfillmentSourcePort.Facts>
                    source,
            Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.RETAIL;
    }

    @Override
    public Facts capture(Query query) {
        if (query == null)
            throw new IllegalArgumentException("Original fulfillment query required");
        var original =
                new IndustryChannelFulfillmentSourcePort.Query(
                        new Scope("MAIN", query.entityNo(), query.storeNo()),
                        query.sourceOrderRef(),
                        query.coreOrderRef(),
                        query.sourceFulfillmentRef(),
                        query.sourceRevision());
        var proof = source.apply(original);
        IndustryChannelFulfillmentSourceFacts.validate(original, proof, clock.instant());
        var data = proof.data();
        return new Facts(
                new Data(
                        query,
                        Stage.valueOf(data.stage()),
                        null,
                        null,
                        null,
                        data.lines().stream()
                                .map(
                                        line ->
                                                new Line(
                                                        line.sourceLineRef(),
                                                        line.componentRef(),
                                                        line.coreItemRef(),
                                                        line.quantity(),
                                                        line.unit()))
                                .toList(),
                        data.occurredAt(),
                        data.receiptNo()),
                proof.observedAt(),
                proof.validUntil(),
                proof.verificationReceiptRef());
    }
}
