package ai.neargo.shop.pay.points.recon;

/** Polls one bounded authoritative page outside pay transactions. */
public interface PointsSourceReconciler {
    Outcome scan(String typeCode, int limit);

    record Outcome(boolean available, int queued, long watermark) {}
}
