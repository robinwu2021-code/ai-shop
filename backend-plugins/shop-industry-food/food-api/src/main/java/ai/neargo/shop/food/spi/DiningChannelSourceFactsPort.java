package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.DiningChannelSourceFacts;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;

import java.time.Instant;

/**
 * Installed bridge reads real CIM publication/order evidence and original FOOD histories; there is
 * no default-success source.
 */
public interface DiningChannelSourceFactsPort {
    DiningChannelSourceFacts read(Key key);

    record CurrentEvidence(
            Key key,
            DiningChannelSourceFacts.Current current,
            Instant observedAt,
            Instant validUntil,
            String sourceReceiptRef) {}

    /**
     * Owners may optimize current-state reads without rebuilding the immutable original
     * composition.
     */
    default CurrentEvidence current(Key key) {
        var facts = read(key);
        return new CurrentEvidence(
                facts.key(),
                facts.current(),
                facts.observedAt(),
                facts.validUntil(),
                facts.sourceReceiptRef());
    }
}
