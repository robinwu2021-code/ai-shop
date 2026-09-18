package ai.neargo.shop.marketing.group.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.marketing.group.GroupService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期未成团的团置为失败（TDD-营销-活动统一模型与集单 §1.3 ①）。
 *
 * <p><b>此前这个任务不存在</b>：{@code mkt_group_buy.end_at} 只在建团时被写，
 * 全仓没有任何东西读它 —— 凑不齐的团永远停在 OPEN，C 端一直显示「还差 N 人」。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class GroupExpireJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupExpireJob.class);

    private final GroupService groupService;
    private final JobSupport jobs;

    public GroupExpireJob(GroupService groupService, JobSupport jobs) {
        this.groupService = groupService;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.group-expire.cron:15 * * * * *}")
    @SchedulerLock(name = "group-expire", lockAtLeastFor = "PT30S", lockAtMostFor = "PT3M")
    public void expire() {
        jobs.run("group-expire", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "group-expire";
    }

    @Bean
    public JobDeclaration groupexpireDeclaration() {
        return new JobDeclaration("group-expire", "拼团到期置失败",
                "把过了截止时间仍未成团的团置为失败。不跑的话凑不齐的团永远显示「还差 N 人」",
                "shop-core", "15 * * * * *", true,
                50, 180,
                true,
                false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = groupService.expireOverdue(System.currentTimeMillis());
        if (n == 0) {
            return JobResult.ok(null);
        }
        log.info("[group] 到期未成团 {} 个，已置失败", n);
        return JobResult.ok(n + " 个团到期未成团，已置失败");
    }
}
