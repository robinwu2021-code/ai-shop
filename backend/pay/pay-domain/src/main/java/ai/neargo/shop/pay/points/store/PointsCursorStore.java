package ai.neargo.shop.pay.points.store;

/** Watermarks advance only after every source event in a page has a durable destination. */
public interface PointsCursorStore {
    long current(String taskName);
    void advance(String taskName,String typeCode,long expected,long next,String payload);
}
