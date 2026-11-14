package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.trade.job.OrderPointsRecoveryJob;
import org.junit.jupiter.api.Test;


class OrderPointsRecoveryJobLeaseTest {
    @Test
    void lease_outlives_declared_maximum_job_duration() throws Exception {
        var job = new OrderPointsRecoveryJob(null, 1);
        long leaseSeconds = job.orderPointsRecoveryDeclaration().lockAtMostSec();
        assertThat(leaseSeconds).isGreaterThan(job.orderPointsRecoveryDeclaration().timeoutSec());
        assertThat(leaseSeconds).isEqualTo(job.orderPointsRecoveryDeclaration().lockAtMostSec());
    }
}
