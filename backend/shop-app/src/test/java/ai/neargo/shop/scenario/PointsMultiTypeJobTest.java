package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ai.neargo.job.api.*;
import ai.neargo.shop.pay.points.job.PointsJobServices;
import ai.neargo.shop.payclient.PointsJobHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class PointsMultiTypeJobTest {
    private PointsJobHandlers handlers() {
        return new PointsJobHandlers(new StaticListableBeanFactory().getBeanProvider(java.time.Clock.class));
    }
    private JobInvocation invocation(Map<String,String> params) {
        return new JobInvocation("TEST", TriggerType.MANUAL, LocalDate.of(2026,10,25), params);
    }
    @Test void multi_type_recovery_deduplicates_and_rotates_first_type() {
        var jobs=mock(PointsJobServices.class);
        when(jobs.run(any(),any(),anyInt(),anyLong(),any())).thenReturn(new PointsJobServices.Result(true,1,0,0));
        var handler=handlers().pointsRecoveryHandler(jobs);
        var in=invocation(Map.of("typeCodes"," SYS_TOKEN,HX_TOKEN,SYS_TOKEN ","limit","50"));
        assertThat(handler.run(in).status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(handler.run(in).status()).isEqualTo(JobStatus.SUCCESS);
        var types=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jobs,times(4)).run(eq(PointsJobServices.Task.RECOVERY),types.capture(),eq(50),anyLong(),eq(in.bizDate()));
        assertThat(types.getAllValues()).containsExactly("SYS_TOKEN","HX_TOKEN","HX_TOKEN","SYS_TOKEN");
        verifyNoMoreInteractions(jobs);
    }
    @Test void failure_is_attributed_and_does_not_block_other_types() {
        var jobs=mock(PointsJobServices.class);
        when(jobs.run(any(),eq("SYS_TOKEN"),anyInt(),anyLong(),any())).thenThrow(new IllegalStateException("POINTS_TEST_FAILURE"));
        when(jobs.run(any(),eq("HX_TOKEN"),anyInt(),anyLong(),any())).thenReturn(new PointsJobServices.Result(true,2,3,0));
        var result=handlers().pointsRecoveryHandler(jobs).run(invocation(Map.of("typeCodes","SYS_TOKEN,HX_TOKEN")));
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error()).isEqualTo("POINTS_TEST_FAILURE");
        assertThat(result.detail()).contains("SYS_TOKEN","HX_TOKEN","2","3");
        verify(jobs).run(eq(PointsJobServices.Task.RECOVERY),eq("HX_TOKEN"),eq(50),anyLong(),any());
    }
    @Test void invalid_or_conflicting_scopes_do_not_run_any_type() {
        for(var params:List.of(Map.of("typeCode","SYS_TOKEN","typeCodes","HX_TOKEN"),
            Map.of("typeCodes","SYS_TOKEN,,HX_TOKEN"), Map.of("typeCodes","SYS_TOKEN,"),
            Map.of("typeCodes","A,B,C,D,E,F,G,H,I"), Map.of("typeCodes","*"))) {
            var jobs=mock(PointsJobServices.class);
            assertThat(handlers().pointsRecoveryHandler(jobs).run(invocation(params)).status()).isEqualTo(JobStatus.FAILED);
            verifyNoInteractions(jobs);
        }
    }
    @Test void legacy_single_type_and_unscoped_dispatch_stay_compatible() {
        var jobs=mock(PointsJobServices.class);
        when(jobs.run(any(),nullable(String.class),anyInt(),anyLong(),any())).thenReturn(new PointsJobServices.Result(true,0,0,0));
        assertThat(handlers().pointsRecoveryHandler(jobs).run(invocation(Map.of("typeCode","SYS_TOKEN"))).status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(handlers().pointsDispatchHandler(jobs).run(invocation(Map.of())).status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(handlers().pointsRecoveryHandler(jobs).run(invocation(Map.of())).status()).isEqualTo(JobStatus.SKIPPED);
        verify(jobs).run(eq(PointsJobServices.Task.RECOVERY),eq("SYS_TOKEN"),eq(50),anyLong(),any());
        verify(jobs).run(eq(PointsJobServices.Task.DISPATCH),isNull(),eq(50),anyLong(),any());
        verifyNoMoreInteractions(jobs);
    }
    @Test void partial_dependency_and_manual_results_are_not_reported_as_success() {
        var jobs=mock(PointsJobServices.class);
        when(jobs.run(any(),eq("SYS_TOKEN"),anyInt(),anyLong(),any())).thenReturn(new PointsJobServices.Result(false,0,1,0));
        when(jobs.run(any(),eq("HX_TOKEN"),anyInt(),anyLong(),any())).thenReturn(new PointsJobServices.Result(true,1,0,0));
        var handler=handlers().pointsSourceHandler(jobs);var in=invocation(Map.of("typeCodes","SYS_TOKEN,HX_TOKEN"));
        assertThat(handler.run(in).status()).isEqualTo(JobStatus.SKIPPED);
        when(jobs.run(any(),eq("HX_TOKEN"),anyInt(),anyLong(),any())).thenReturn(new PointsJobServices.Result(true,1,0,2));
        assertThat(handler.run(in).status()).isEqualTo(JobStatus.FAILED);
    }
    @Test void order_recovery_is_registered_without_enabling_it_or_self_scheduling() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withPropertyValues("shop.job.enabled=true")
            .withBean(ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.class,
                () -> mock(ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.class))
            .withUserConfiguration(ai.neargo.shop.trade.job.OrderPointsRecoveryJob.class)
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(ai.neargo.shop.trade.job.OrderPointsRecoveryJob.class);
                assertThat(ctx.getBean(ai.neargo.shop.trade.job.OrderPointsRecoveryJob.class).orderPointsRecoveryDeclaration().enabled()).isFalse();
                assertThat(java.util.Arrays.stream(ai.neargo.shop.trade.job.OrderPointsRecoveryJob.class.getMethods())
                    .anyMatch(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class))).isFalse();
            });
    }

    @Test void legacy_code_is_literal_not_reinterpreted_as_a_new_scope() {
        var jobs=mock(PointsJobServices.class);
        when(jobs.run(any(),any(),anyInt(),anyLong(),any())).thenReturn(new PointsJobServices.Result(true,0,0,0));
        var result=handlers().pointsRecoveryHandler(jobs).run(invocation(Map.of("typeCode","points.v2")));
        assertThat(result.status()).isEqualTo(JobStatus.SUCCESS);
        verify(jobs).run(eq(PointsJobServices.Task.RECOVERY),eq("points.v2"),eq(50),anyLong(),any());
    }
    @Test void order_recovery_rejects_invalid_batch_before_running_work() {
        var service=mock(ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.class);
        var job=new ai.neargo.shop.trade.job.OrderPointsRecoveryJob(service,200);
        for(String limit:List.of("0","201","invalid"))
            assertThat(job.run(invocation(Map.of("limit",limit))).status()).isEqualTo(JobStatus.FAILED);
        verifyNoInteractions(service);
    }
    @Test void order_recovery_preserves_failure_and_backlog_details() {
        var service=mock(ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.class);
        var item=new ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.QueueBacklog(2,30);
        when(service.recover(10)).thenReturn(new ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.Report(3,2,List.of(new ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.Failure("old-command","POINTS_PENDING"))));
        when(service.backlog(any())).thenReturn(new ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.Backlog(item,item,item,item));
        var result=new ai.neargo.shop.trade.job.OrderPointsRecoveryJob(service,200).run(invocation(Map.of("limit","10")));
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);assertThat(result.error()).isEqualTo("POINTS_PENDING");
        assertThat(result.detail()).contains("2/30s","扫描 3 项");
        verify(service).recover(10);
    }
    @Test void explicit_initial_enable_property_is_still_respected() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withPropertyValues("shop.job.enabled=true","shop.points.order-recovery.enabled=true","shop.points.order-recovery.cron=0 */3 * * * *")
            .withBean(ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.class,
                () -> mock(ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.class))
            .withUserConfiguration(ai.neargo.shop.trade.job.OrderPointsRecoveryJob.class)
            .run(ctx -> {
                var declaration=ctx.getBean(ai.neargo.shop.trade.job.OrderPointsRecoveryJob.class).orderPointsRecoveryDeclaration();
                assertThat(declaration.enabled()).isTrue();assertThat(declaration.defaultCron()).isEqualTo("0 */3 * * * *");
            });
    }
}
