package ai.neargo.shop.pay.points.event;

/** Bounded durable work queue. Delivery is at least once; handlers own domain idempotency. */
public interface PointsEventQueue {
    enum Kind { SOURCE_FACT, EARN_GRANT, REFUND_CONFIRMATION, REFUND_CLOSURE, ZERO_CASH, EXPIRY_REMINDER }
    record Message(Kind kind,String payload) { }
    record Lease(long id,long version,String typeCode,String eventNo,Message message,int attempt) { }
    void enqueue(String typeCode,String eventNo,Message message);
    Lease claim(long now);
    Lease claim(String typeCode,long now);
    void complete(Lease lease);
    void fail(Lease lease,long now,boolean permanent,String reason);
    void retry(long id,String actor,String reason,long now);
}
