package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.trade.job.OrderPointsRecoveryJob;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

class OrderPointsRecoveryJobLeaseTest {
    @Test
    void lease_outlives_declared_maximum_job_duration() throws Exception {
        var job = new OrderPointsRecoveryJob(null, null, 1);
        var lock = OrderPointsRecoveryJob.class.getMethod("scan").getAnnotation(SchedulerLock.class);
        long leaseSeconds = Duration.parse(lock.lockAtMostFor()).getSeconds();
        assertThat(leaseSeconds).isGreaterThan(job.orderPointsRecoveryDeclaration().timeoutSec());
        assertThat(leaseSeconds).isEqualTo(job.orderPointsRecoveryDeclaration().lockAtMostSec());
    }
}
