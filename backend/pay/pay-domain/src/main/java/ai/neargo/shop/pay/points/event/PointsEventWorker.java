package ai.neargo.shop.pay.points.event;

/**
 * Runs remote or cross-domain dispatch outside pay transactions, then acknowledges the original
 * lease.
 */
public interface PointsEventWorker {
    record Outcome(int succeeded, int retried, int manual) {}

    Outcome process(String typeCode, int limit, long now);

    int drain(int limit, long now);

    int drain(String typeCode, int limit, long now);
}
