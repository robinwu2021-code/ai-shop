package ai.neargo.shop.trade.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.service.IndustryTransactionRecoveryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Query-only bounded recovery of original source transactions; never schedules a new payment. */
@Component
@ConditionalOnProperty(name="shop.job.enabled",havingValue="true")
public class IndustryTransactionRecoveryJob implements JobHandler {
    private static final String NAME="industry-transaction-recovery";
    private final IndustryTransactionRecoveryService recovery;
    private final JobSupport jobs;
    public IndustryTransactionRecoveryJob(IndustryTransactionRecoveryService recovery,JobSupport jobs) {
        this.recovery=recovery; this.jobs=jobs;
    }
    @Scheduled(cron="${shop.job.industry-transaction-recovery.cron:0 */2 * * * *}")
    @SchedulerLock(name=NAME,lockAtLeastFor="PT10S",lockAtMostFor="PT110S")
    public void scan() { jobs.run(NAME,()->run(null).detail()); }
    @Override public String name() { return NAME; }
    @Override public JobResult run(JobInvocation invocation) {
        var result=recovery.recoverDue(System.currentTimeMillis(),200);
        return JobResult.ok("claimed="+result.claimed()+", complete="+result.complete()
                +", pending="+result.pending()+", failed="+result.failed());
    }
    @Bean public JobDeclaration industryTransactionRecoveryDeclaration() {
        return new JobDeclaration(NAME,"Industry transaction recovery",
                "Query original payment receipts, project confirmed cash and recover original prepared cancellations; never initiate payment.",
                "shop-core","0 */2 * * * *",true,100,110,true,true);
    }
}
