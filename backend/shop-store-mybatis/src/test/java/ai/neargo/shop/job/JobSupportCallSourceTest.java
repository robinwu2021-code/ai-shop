package ai.neargo.shop.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSources;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code @Scheduled} 入口经 {@link JobSupport#run} 跑任务体（开发计划 M2.7）：任务体里调支付，来源记成这个任务 ——
 * 补偿查单按来源端 JOB 抽样靠它。
 */
class JobSupportCallSourceTest {

    @Test
    @DisplayName("★★★ 任务体里取到的来源：来源端 JOB、上游接口 = 任务名；跑完解绑")
    void bodySeesJobOrigin() {
        AtomicReference<CallSource> inside = new AtomicReference<>();
        new JobSupport(mock(JobSupport.JobRunMapper.class)).run("industry-transaction-recovery", () -> {
            inside.set(CallSources.current());
            return null;
        });

        assertThat(inside.get().originClient()).isEqualTo(CallSources.JOB);
        assertThat(inside.get().originApi()).isEqualTo("industry-transaction-recovery");
        assertThat(CallSources.current().originClient()).isEqualTo(CallSources.UNKNOWN);
    }
}
