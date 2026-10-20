package ai.neargo.shop.pay.job;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssueRecoveryService;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssueRecoveryService.Result;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
class LegacyCouponIssueRecoveryJobTest {
    @Test void bothSwitchesAreRequiredAndManualRunRotatesABoundedCursor() {
        var service=mock(LegacyCouponIssueRecoveryService.class);
        var runner=new ApplicationContextRunner().withUserConfiguration(LegacyCouponIssueRecoveryJob.class)
                .withBean(LegacyCouponIssueRecoveryService.class,()->service).withBean(JobSupport.class,()->mock(JobSupport.class));
        runner.run(context->assertThat(context).doesNotHaveBean(LegacyCouponIssueRecoveryJob.class));
        runner.withPropertyValues("shop.job.enabled=true").run(context->assertThat(context).doesNotHaveBean(LegacyCouponIssueRecoveryJob.class));
        runner.withPropertyValues("shop.job.enabled=true","shop.pay.entitlement.legacy-coupon.issue-recovery-enabled=true")
                .run(context->assertThat(context).hasSingleBean(LegacyCouponIssueRecoveryJob.class));
        when(service.recover("MAIN",0,20)).thenReturn(new Result(10,7,2,1,42));
        when(service.recover("MAIN",42,20)).thenReturn(new Result(0,0,0,0,0));
        var job=new LegacyCouponIssueRecoveryJob(service,mock(JobSupport.class));
        assertThat(job.run(null).status()).isEqualTo(ai.neargo.job.api.JobStatus.FAILED);
        job.run(null);job.run(null);
        verify(service,times(2)).recover("MAIN",0,20);verify(service).recover("MAIN",42,20);
    }
}
