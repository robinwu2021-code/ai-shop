package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.job.api.TriggerType;
import ai.neargo.shop.job.JobHandlerRegistry;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSources;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 定时任务里调支付，来源记成这个任务（开发计划 M2.1；M2.7 按来源端 JOB 抽样查单靠它）。
 */
class InProcessJobCallSourceTest {

    @Test
    @DisplayName("★★★ 任务体里取到的来源：来源端 JOB、上游接口 = handler 名、操作人 SYSTEM；跑完解绑")
    void jobSeesJobOrigin() {
        List<CallSource> seen = new ArrayList<>();
        JobHandler handler = new JobHandler() {
            @Override
            public String name() {
                return "pay-order-compensation";
            }

            @Override
            public JobResult run(JobInvocation invocation) {
                seen.add(CallSources.current());
                return JobResult.ok("ok");
            }
        };

        new InProcessJobConfig().localJobInvoker(new JobHandlerRegistry(List.of(handler), List.of()))
                .invoke("LOCAL", "pay-order-compensation", new JobInvocation("run-1", TriggerType.MANUAL, null, Map.of()), 10);

        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.originClient()).isEqualTo(CallSources.JOB);
            assertThat(s.originApi()).isEqualTo("pay-order-compensation");
            assertThat(s.operatorNo()).isEqualTo(CallSources.SYSTEM);
        });
        assertThat(CallSources.current().originClient()).isEqualTo(CallSources.UNKNOWN);
    }
}
