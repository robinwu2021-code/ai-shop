package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.payclient.EntitlementLifecycleAppService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expiry is a pay-owned lifecycle mutation; it never erases a balance or cancels an existing commitment. */
@Component
@ConditionalOnProperty(name="shop.job.enabled",havingValue="true")
public class EntitlementExpiryJob implements JobHandler {
    private final EntitlementLifecycleAppService pay;
    private final JobSupport jobs;
    public EntitlementExpiryJob(EntitlementLifecycleAppService pay,JobSupport jobs) { this.pay=pay; this.jobs=jobs; }
    @Override public String name() { return "entitlement-expiry"; }
    @Scheduled(cron="${shop.job.entitlement-expiry.cron:45 */5 * * * *}")
    @SchedulerLock(name="entitlement-expiry",lockAtMostFor="PT4M")
    public void tick() { jobs.run(name(),() -> run(null).detail()); }
    @Bean public JobDeclaration entitlementExpiryDeclaration() {
        return new JobDeclaration(name(),"权益到期处理","仅到期状态迁移，余额和原预占保留",
                "shop-app","45 */5 * * * *",true,240,300,true,true);
    }
    @Override public JobResult run(JobInvocation invocation) {
        var ctx=new EntitlementContext("MAIN","ENTITLEMENT_EXPIRY",name(),"0".repeat(64));
        int changed=pay.expire(ctx,100);
        return JobResult.ok(changed==0?null:"到期处理%d项".formatted(changed));
    }
}
