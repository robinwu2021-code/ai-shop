package ai.neargo.shop.spi.points;

import java.util.Optional;

/** Source-owned immutable journal; never reconstruct historical messages from current orders. */
public interface OrderPointsSourcePort {
    Optional<PointsOrderEvent> find(String eventNo);

    SourceFactsPort.Page scan(String typeCode, long after, int limit);
}
