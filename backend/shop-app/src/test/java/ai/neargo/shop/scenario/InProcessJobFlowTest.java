package ai.neargo.shop.scenario;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.engine.JobRegistry;
import ai.neargo.job.engine.JobSyncService;
import ai.neargo.job.store.JobDefinitionDao;
import ai.neargo.job.store.JobDefinitionRow;
import ai.neargo.shop.job.JobHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>业务实例内的 worker 真的把任务排上了吗。</b>
 *
 * <p>这是 14 个任务从上线至今第一次真的会跑 —— 而「会跑」这件事在此之前
 * 没有任何测试覆盖：{@code sys_job_run} 0 行、{@code shedlock} 0 行，
 * 因为生产跑 {@code api,ops} 而任务全挂在 {@code worker} 上。
 */
// **不起 web**：worker profile 自己就把 web-application-type 设成 none
// （批量任务与 API 抢线程池时，结算跑一轮能把下单拖到超时）。
// 用默认的 MOCK web 会因为拿不到 HttpSecurity 而起不来，而报错完全不指向 profile
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "worker"})
@TestPropertySource(properties = {
        // **自带一个平台库**。@TestPropertySource 会造出第二个 Spring 上下文，
        // 而默认的 jdbc:h2:mem:shop 是 DB_CLOSE_DELAY=-1 的共享库 ——
        // schema-test.sql 会在同一个库上跑第二遍，报 sys_industry 主键冲突。
        // 症状是「单独跑绿、全量跑红」，而报错与被测的东西毫无关系
        "spring.datasource.url=jdbc:h2:mem:inproc-platform;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.job.enabled=true",
        "shop.job.flyway-locations=classpath:db/job-h2",
        "shop.job.datasource.url=jdbc:h2:mem:inprocjob;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.job.datasource.username=sa",
        "shop.job.datasource.password=",
        // 轮询给足够长：这组用例自己手动触发同步，不靠定时器
        "shop.job.worker.poll-interval=3600s",
})
@DisplayName("业务实例内的定时任务")
class InProcessJobFlowTest {

    @Autowired
    JobHandlerRegistry handlers;

    @Autowired
    JobSyncService sync;

    @Autowired
    JobRegistry registry;

    @Autowired
    JobDefinitionDao definitions;

    @Test
    @DisplayName("★ 各域交出的 handler 都被收进来了，且每个都有声明")
    void handlersAreCollected() {
        assertThat(handlers.size())
                .as("一个 handler 都没收到 —— 那 14 个任务会全部「有声明没实现」")
                .isGreaterThanOrEqualTo(10);

        List<String> declared = handlers.declarations().stream()
                .map(JobDeclaration::handlerName).toList();
        assertThat(declared).contains("order-auto-close", "plan-expiry", "recon-scan",
                "points-activate", "points-expire");
    }

    @Test
    @DisplayName("★★ 同步一次之后，任务进了 job_definition 并被排上调度")
    void syncRegistersEveryJob() {
        sync.syncOnce();

        List<JobDefinitionRow> rows = definitions.findAll();
        assertThat(rows).as("同步完 job_definition 还是空的 —— 那运营页面上什么都看不到")
                .isNotEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.displayName())
                    .as("%s 的中文名是空的 —— 运营页面会显示一个锁名，没人看得懂", r.jobName())
                    .isNotBlank();
            assertThat(r.cron()).isNotBlank();
        });

        assertThat(registry.scheduledNames())
                .as("库里有定义却一个都没排上 —— 任务仍然不会跑")
                .isNotEmpty();
    }

    @Test
    @DisplayName("★ 每个 handler 的中文名都是给人看的，不是锁名")
    void displayNamesAreHumanReadable() {
        for (JobDeclaration d : handlers.declarations()) {
            assertThat(d.displayName())
                    .as("%s 的 displayName 还是锁名", d.handlerName())
                    .isNotEqualTo(d.handlerName());
            assertThat(d.description()).as("%s 没有说明", d.handlerName()).isNotBlank();
        }
    }
}
