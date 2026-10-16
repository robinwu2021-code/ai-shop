package ai.neargo.shop.pay.points.event;

/** Runs remote or cross-domain dispatch outside pay transactions, then acknowledges the original lease. */
public interface PointsEventWorker {
    int drain(int limit,long now);
    int drain(String typeCode,int limit,long now);
}
