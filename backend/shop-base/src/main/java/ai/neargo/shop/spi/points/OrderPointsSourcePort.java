package ai.neargo.shop.spi.points;

import java.util.Optional;

/** Source-owned immutable journal; never reconstruct historical messages from current orders. */
public interface OrderPointsSourcePort {
    /** Default denies detachment until the real source journal proves delivery and closure. */
    default boolean settled(String storeNo) { return false; }
    default boolean settledSubOrder(String subOrderNo) { return false; }
    Optional<PointsOrderEvent> find(String eventNo);

    SourceFactsPort.Page scan(String typeCode, long after, int limit);
}
