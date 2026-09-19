package ai.neargo.shop.member.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.member.service.MemberLevelService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会员分层每日重算（TDD-会员标签与定向营销 §2.3 ①）。
 *
 * <p><b>此前这个任务不存在</b>：{@code mbr_member.d90_order_count} 的列注释写着「每日重算」，
 * 分层却只在支付成功时算 —— 一个人不再来，他就永远停在最后一次的分层上，
 * 会员页的「沉睡」只会越来越少于真实人数，而没有任何东西会响。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class MemberLevelRecomputeJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MemberLevelRecomputeJob.class);
    private static final String NAME = "member-level-recompute";

    private final MemberLevelService levelService;
    private final JobSupport jobs;

    public MemberLevelRecomputeJob(MemberLevelService levelService, JobSupport jobs) {
        this.levelService = levelService;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.member-level-recompute.cron:0 0 3 * * *}")
    @SchedulerLock(name = NAME, lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void recompute() {
        jobs.run(NAME, () -> run(null).detail());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Bean
    public JobDeclaration memberlevelrecomputeDeclaration() {
        return new JobDeclaration(NAME, "会员分层重算",
                "按口径重算全部会员的分层与近 90 天单数。不跑的话不再下单的人永远不会变成「沉睡」",
                "shop-core", "0 0 3 * * *", true,
                60_000, 1800,
                true,
                false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        MemberLevelService.RecomputeResult r = levelService.recompute(System.currentTimeMillis());
        if (r.changed() == 0) {
            return JobResult.ok(null);
        }
        log.info("[会员分层] 扫描 {} 人，变更 {} 人，其中新变沉睡 {} 人，用时 {} ms",
                r.scanned(), r.changed(), r.newlySleeping(), r.tookMs());
        return JobResult.ok("变更 %d 人（新变沉睡 %d）".formatted(r.changed(), r.newlySleeping()));
    }
}
