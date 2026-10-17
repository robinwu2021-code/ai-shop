package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.spi.points.*;

/** Append joins an existing source transaction; delivery acknowledgement is separately durable. */
public interface OrderPointsFactService {
    java.util.Optional<PointsOrderEvent> find(String eventNo);
    SourceFactsPort.Page scan(String typeCode,long after,int limit);
    boolean settled(String storeNo);
    boolean settledSubOrder(String subOrderNo);
    PointsOrderEvent append(PointsOrderEvent draft);

    void acknowledge(String eventNo);

    java.util.List<String> pending(int limit);
}
