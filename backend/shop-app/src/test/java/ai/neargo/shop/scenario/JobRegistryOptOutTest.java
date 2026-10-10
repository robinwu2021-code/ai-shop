package ai.neargo.shop.scenario;

import ai.neargo.job.engine.JobRegistry;
import ai.neargo.job.engine.JobSyncService;
import ai.neargo.shop.job.JobHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「跑任务」与「参与调度」是两件事。
 *
 * <h2>这条测试的由来</h2>
 * <p>2026-08-28 生产上需要起一个一次性的进销存搬运进程。它必须开
 * {@code shop.job.enabled=true} —— 否则 {@code InventoryBackfillJob} 这个 bean
 * 根本不存在。而那个开关<b>连带把进程内调度器也带了起来</b>：调度器没配 targets，
 * 退到占位名 LOCAL，把生产 job 库 12 行的 target 每 30 秒覆写一次，
 * 独立调度器因此一个任务都排不上。
 *
 * <p>当时唯一想得到的建议是「加 {@code --shop.job.enabled=false}」——
 * <b>而那是错的</b>：它会把要跑的那个任务一起关掉。
 * 能想到的唯一办法是错的，说明缺的是一个开关，不是使用者不小心。
 */
class JobRegistryOptOutTest {

    /** 一次性 worker 的形态：要任务体，不要注册表。 */
    @Nested
    @SpringBootTest
    @ActiveProfiles({"test", "worker"})
    @TestPropertySource(properties = {
            "spring.datasource.url=jdbc:h2:mem:optout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "shop.job.enabled=true",
            "shop.job.flyway-locations=classpath:db/job-h2",
            "shop.job.datasource.url=jdbc:h2:mem:optoutjob;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "shop.job.datasource.username=sa",
            "shop.job.datasource.password=",
            "shop.job.registry.enabled=false",
    })
    @DisplayName("registry.enabled=false")
    class OptedOut {

        @Autowired
        ApplicationContext ctx;

        @Autowired
        JobHandlerRegistry handlers;

        @Test
        @DisplayName("★★★ 任务体还在 —— 关掉的是「参与调度」，不是「能不能跑」")
        void handlersStillExist() {
            assertThat(handlers.declarations())
                    .as("把任务体一起关掉的话，这个进程就没法跑它要跑的那个任务了")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("★★★ 但不碰共享注册表：既不写 job_definition，也不排期")
        void doesNotJoinTheSharedRegistry() {
            assertThat(ctx.getBeanNamesForType(JobSyncService.class))
                    .as("它一起来就会把 12 行的 target 覆写成自己的")
                    .isEmpty();
            assertThat(ctx.getBeanNamesForType(JobRegistry.class)).isEmpty();
        }
    }

    /** 默认形态：不配这个开关时，行为与从前一样。 */
    @Nested
    @SpringBootTest
    @ActiveProfiles({"test", "worker"})
    @TestPropertySource(properties = {
            "spring.datasource.url=jdbc:h2:mem:optin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "shop.job.enabled=true",
            "shop.job.flyway-locations=classpath:db/job-h2",
            "shop.job.datasource.url=jdbc:h2:mem:optinjob;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "shop.job.datasource.username=sa",
            "shop.job.datasource.password=",
    })
    @DisplayName("不配这个开关")
    class DefaultOn {

        @Autowired
        ApplicationContext ctx;

        @Test
        @DisplayName("★ 默认仍然参与调度 —— 新开关不能改变既有部署的行为")
        void registryStillOnByDefault() {
            assertThat(ctx.getBeanNamesForType(JobSyncService.class)).isNotEmpty();
            assertThat(ctx.getBeanNamesForType(JobRegistry.class)).isNotEmpty();
        }
    }
}
