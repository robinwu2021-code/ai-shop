package ai.neargo.shop.sessionjob;

import org.springframework.context.annotation.Bean;
import ai.neargo.auth.store.LoginLogDao;
import ai.neargo.auth.store.SessionProfile;
import ai.neargo.auth.store.SessionDao;
import ai.neargo.shop.config.SessionProfiles;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 清理三端的会话与登录日志。**这张表自己会长，所以清理要和表一起交付。**
 *
 * <p>两条各自不同的保留规则：
 * <ul>
 *   <li><b>会话</b>：只删**过期很久**的（默认再留 30 天）。
 *       <b>不按 {@code revoked_at} 删</b> —— 软撤销的行是审计资料，
 *       「我为什么突然被登出」要查得到。</li>
 *   <li><b>登录日志</b>：按各端自己的保留期（C 端 90 天 / B 端 180 天 / 运营端 730 天）。
 *       运营端留两年，是因为运营操作要能追溯。</li>
 * </ul>
 *
 * <p><b>分批删</b>而不是一条 DELETE 删干净：一次删几十万行会长时间持锁，
 * 而这两张表同时正被登录写入。
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "db")
public class SessionPurgeJob implements JobHandler {

    /** 会话过期之后再留多久才物理删。留一段是为了让「他上次什么时候掉线的」还查得到。 */
    private static final int SESSION_GRACE_DAYS = 30;

    /** 单批行数与单轮批次上限。剩下的下一轮接着删 —— 一轮占太久会挡住登录写入。 */
    private static final int BATCH = 1000;
    private static final int MAX_BATCHES = 20;

    private final JdbcClient jdbc;
    private final JobSupport jobs;

    public SessionPurgeJob(JdbcClient authJdbcClient, JobSupport jobs) {
        this.jdbc = authJdbcClient;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.session-purge.cron:0 15 4 * * *}")
    @SchedulerLock(name = "session-purge", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void purge() {
        // 触发器只负责「到点了」；任务体在 run() 里。J1 只搬不改
        jobs.run("session-purge", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "session-purge";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration sessionpurgeDeclaration() {
        return JobDeclaration.daily("session-purge", "会话与登录日志清理",
                "删掉过期很久的会话与超出保留期的登录日志。不跑的话登录日志会长成本库最大的表",
                "shop-app", "0 15 4 * * *");
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        LocalDateTime now = LocalDateTime.now();
        int sessions = 0;
        int logs = 0;
        for (SessionProfile p : List.of(SessionProfiles.CONSUMER,
                SessionProfiles.MERCHANT, SessionProfiles.OPERATOR)) {
            sessions += purgeBatched(b ->
                    new SessionDao(jdbc, p).purgeExpiredBefore(
                            now.minusDays(SESSION_GRACE_DAYS), b));
            logs += purgeBatched(b ->
                    new LoginLogDao(jdbc, p).purgeBefore(
                            now.minusDays(p.logRetentionDays()), b));
        }
        if (sessions == 0 && logs == 0) {
            // **detail 保持 null** —— JobSupport 用它区分「跑了但没事」
            return JobResult.ok(null);
        }
        return JobResult.ok("清理会话 %d 行、登录日志 %d 行".formatted(sessions, logs));
    }

    private interface Batch {
        int delete(int batchSize);
    }

    private static int purgeBatched(Batch op) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = op.delete(BATCH);
            total += deleted;
            if (deleted < BATCH) {
                break;
            }
        }
        return total;
    }
}
