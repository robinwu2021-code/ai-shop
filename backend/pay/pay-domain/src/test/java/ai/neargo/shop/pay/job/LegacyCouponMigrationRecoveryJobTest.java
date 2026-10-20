package ai.neargo.shop.pay.job;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationRecoveryService;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationRecoveryService.Batch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
class LegacyCouponMigrationRecoveryJobTest {
    @Test void bothSwitchesAreRequiredAndManualRunRotatesABoundedCursor() {
        var service=mock(LegacyCouponMigrationRecoveryService.class);
        var runner=new ApplicationContextRunner().withUserConfiguration(LegacyCouponMigrationRecoveryJob.class)
                .withBean(LegacyCouponMigrationRecoveryService.class,()->service).withBean(JobSupport.class,()->mock(JobSupport.class));
        runner.run(context->assertThat(context).doesNotHaveBean(LegacyCouponMigrationRecoveryJob.class));
        runner.withPropertyValues("shop.job.enabled=true").run(context->assertThat(context).doesNotHaveBean(LegacyCouponMigrationRecoveryJob.class));
        runner.withPropertyValues("shop.job.enabled=true","shop.pay.entitlement.migration-recovery.enabled=true")
                .run(context->assertThat(context).hasSingleBean(LegacyCouponMigrationRecoveryJob.class));
        when(service.recover("MAIN",0,10)).thenReturn(new Batch(10,7,2,1,42));
        when(service.recover("MAIN",42,10)).thenReturn(new Batch(0,0,0,0,0));
        var job=new LegacyCouponMigrationRecoveryJob(service,mock(JobSupport.class));
        assertThat(job.run(null).status()).isEqualTo(ai.neargo.job.api.JobStatus.FAILED);
        job.run(null);job.run(null);
        verify(service,times(2)).recover("MAIN",0,10);verify(service).recover("MAIN",42,10);
    }
}
