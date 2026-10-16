package ai.neargo.shop.pay.points.job;

import java.time.LocalDate;

/**
 * Host-neutral bounded job bodies; the app owns scheduler declarations and operational
 * presentation.
 */
public interface PointsJobServices {
    enum Task {
        SOURCE,
        DISPATCH,
        RECOVERY,
        LIFECYCLE,
        RECON,
        STATS_PROJECT,
        STATS_REBUILD,
        PLANS
    }

    record Result(boolean available, int processed, int deferred, long manual) {}

    Result run(Task task, String typeCode, int limit, long now, LocalDate businessDate);
}
