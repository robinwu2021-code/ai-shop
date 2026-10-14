package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import java.util.Map;

/** Durable non-financial source metadata joins the posting transaction. */
public interface PointsFactStore {
    Map<String,Object> inbox(String eventNo);
    void record(LifecycleFact fact, String payload, String state);
    void saveLifecycle(LifecycleFact fact, String payload, Long expectedVersion);
}
