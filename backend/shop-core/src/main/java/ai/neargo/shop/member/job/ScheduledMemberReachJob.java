package ai.neargo.shop.member.job;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.member.service.ScheduledReachService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** 调度和租约由 JobRunner 承担，运营停用任务后不会继续本地触发。 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class ScheduledMemberReachJob implements JobHandler {
    private final ScheduledReachService reach;

    public ScheduledMemberReachJob(ScheduledReachService reach) {
        this.reach = reach;
    }

    @Override
    public String name() {
        return "scheduled-member-reach";
    }

    @Bean
    public JobDeclaration scheduledMemberReachDeclaration() {
        return new JobDeclaration(name(), "预约会员触达", "到期确定受众，逐人投递并恢复未完成任务",
                "shop-core", "15 * * * * *", true, 50, 180, true, false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int processed = DataScopeContext.executeWithoutScope(() -> reach.dispatchDue(100));
        return JobResult.ok(processed == 0 ? null : "已处理 " + processed + " 名预约收件人");
    }
}
