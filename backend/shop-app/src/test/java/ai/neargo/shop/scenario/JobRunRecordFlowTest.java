package ai.neargo.shop.scenario;

import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.job.SysJobRun;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务运行记录。
 *
 * <p><b>这张表存在的理由</b>：这一轮撞到过 `OutboxDispatcher` 写好了、
 * 而调度任务从来没被写出来 —— 全站站内信一条都发不出去，测试却全绿。
 * 有运行记录的话，「从来没有一条 outbox 的记录」第一天就会露出来。
 *
 * <p>自己一个内存库，理由见 {@code OtpRateLimitFlowTest} 顶部。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:job-run;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
@DisplayName("定时任务运行记录")
class JobRunRecordFlowTest {

    @Autowired
    private JobSupport jobs;

    @Autowired
    private JobSupport.JobRunMapper mapper;

    private SysJobRun row(String name) {
        return mapper.selectOne(Wrappers.<SysJobRun>lambdaQuery()
                .eq(SysJobRun::getJobName, name).last("limit 1"));
    }

    @Test
    @DisplayName("★★★ 任务抛异常时不往外抛，但必须留下 FAILED 记录 —— 吞了不等于忽略")
    void failureIsSwallowedButRecorded() {
        // 不抛出去才是对的：抛出去 Spring 只打一行 ERROR，下一轮照跑，
        // 看着像没事而实际每轮都在失败
        jobs.run("test-boom", () -> {
            throw new IllegalStateException("通道挂了");
        });

        SysJobRun r = row("test-boom");
        assertThat(r).as("失败了却没留下任何记录 —— 那就回到「静默失败」了").isNotNull();
        assertThat(r.getStatus()).isEqualTo(SysJobRun.FAILED);
        assertThat(r.getError()).contains("通道挂了");
        assertThat(r.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 连续失败会累加，成功即清零 —— 单次失败多半是抖动，连续失败才要人看")
    void consecutiveFailuresResetOnSuccess() {
        for (int i = 0; i < 3; i++) {
            jobs.run("test-flaky", () -> {
                throw new IllegalStateException("网络抖动");
            });
        }
        assertThat(row("test-flaky").getConsecutiveFailures()).isEqualTo(3);

        jobs.run("test-flaky", () -> "干了点事");

        SysJobRun r = row("test-flaky");
        assertThat(r.getConsecutiveFailures())
                .as("**按「连续」计数而不是累计** —— 累计的话跑半年的任务会攒出一个吓人的数字")
                .isZero();
        assertThat(r.getStatus()).isEqualTo(SysJobRun.OK);
        assertThat(r.getDetail()).isEqualTo("干了点事");
        assertThat(r.getRunCount()).as("累计轮数继续涨").isEqualTo(4);
    }

    @Test
    @DisplayName("★★ 什么都没做的那轮也要留记录 —— 「跑过了没事」与「压根没跑」是两件事")
    void idleRunsStillLeaveARecord() {
        jobs.run("test-idle", () -> null);

        SysJobRun r = row("test-idle");
        assertThat(r).isNotNull();
        assertThat(r.getStatus()).isEqualTo(SysJobRun.OK);
        assertThat(r.getDetail())
                .as("这轮没做事，detail 留空 —— 写「成功」是噪音，写「投出 0 条」是假信息")
                .isNull();
        assertThat(r.getLastRunAt()).as("**时间必须有** —— 它回答的正是「到底跑没跑」").isNotNull();
    }

    @Test
    @DisplayName("★★ 一个任务一行，不是一次运行一行 —— outbox 每 5 秒一轮，追加式一天 17000 行")
    void oneRowPerJobNotPerRun() {
        for (int i = 0; i < 5; i++) {
            jobs.run("test-repeat", () -> "第 N 轮");
        }
        long rows = mapper.selectCount(Wrappers.<SysJobRun>lambdaQuery()
                .eq(SysJobRun::getJobName, "test-repeat"));
        assertThat(rows).isEqualTo(1);
        assertThat(row("test-repeat").getRunCount()).isEqualTo(5);
    }
}
