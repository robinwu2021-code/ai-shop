package ai.neargo.shop.pay.points.store;

import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashCompletionFact;

import java.util.Map;

/** Durable non-financial source metadata joins the posting transaction. */
public interface PointsFactStore {
    Map<String, Object> inbox(String eventNo);

    /** Current locking read after type/pool and refund-preparation locks. */
    Map<String, Object> lockInbox(String eventNo);

    void recordZeroCash(ZeroCashCompletionFact fact, String payload, String payloadHash);

    void record(LifecycleFact fact, String payload, String state);

    void saveLifecycle(LifecycleFact fact, String payload, Long expectedVersion);

    void recordEvent(String typeCode, String eventNo, String hash, String payload, String state);
}
