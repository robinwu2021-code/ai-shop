package ai.neargo.shop.pay.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.migration.service.LegacyCouponUsageRepairService;
import ai.neargo.shop.pay.migration.service.LegacyCouponUsageRepairService.Batch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LegacyCouponUsageRepairJobTest {
    @Test void repairNeedsBothExplicitSwitches() {
        var runner=new ApplicationContextRunner().withUserConfiguration(LegacyCouponUsageRepairJob.class)
                .withBean(LegacyCouponUsageRepairService.class,()->mock(LegacyCouponUsageRepairService.class))
                .withBean(JobSupport.class,()->mock(JobSupport.class));
        runner.run(c->assertThat(c).doesNotHaveBean(LegacyCouponUsageRepairJob.class));
        runner.withPropertyValues("shop.job.enabled=true").run(c->assertThat(c).doesNotHaveBean(LegacyCouponUsageRepairJob.class));
        runner.withPropertyValues("shop.job.enabled=true","shop.pay.entitlement.legacy-coupon.usage-repair-enabled=true")
                .run(c->assertThat(c).hasSingleBean(LegacyCouponUsageRepairJob.class));
    }
    @Test void poisonBatchRotatesAndEndResetsCursorWithFixedBound() {
        var service=mock(LegacyCouponUsageRepairService.class);
        when(service.repair(any(),eq(0L),eq(100))).thenReturn(new Batch(100,98,2,42));
        when(service.repair(any(),eq(42L),eq(100))).thenReturn(new Batch(0,0,0,0));
        var job=new LegacyCouponUsageRepairJob(service,mock(JobSupport.class));
        assertThat(job.run(null).status()).isEqualTo(ai.neargo.job.api.JobStatus.FAILED);
        assertThat(job.run(null).status()).isEqualTo(ai.neargo.job.api.JobStatus.SUCCESS);
        assertThat(job.run(null).status()).isEqualTo(ai.neargo.job.api.JobStatus.FAILED);
        verify(service,times(2)).repair(argThat(ctx->"MAIN".equals(ctx.tenantNo()) && "LEGACY_USAGE_REPAIR".equals(ctx.actorNo())),eq(0L),eq(100));
        verify(service).repair(any(),eq(42L),eq(100));
        verifyNoMoreInteractions(service);
    }
}
