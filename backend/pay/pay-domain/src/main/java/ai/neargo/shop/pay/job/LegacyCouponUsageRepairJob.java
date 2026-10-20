package ai.neargo.shop.pay.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.migration.service.LegacyCouponUsageRepairService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Explicit rollout repair, disabled unless both job and projection-repair switches are enabled. */
@Component
@ConditionalOnProperty(name={"shop.job.enabled","shop.pay.entitlement.legacy-coupon.usage-repair-enabled"},havingValue="true")
public class LegacyCouponUsageRepairJob implements JobHandler {
    private final LegacyCouponUsageRepairService repair;
    private final JobSupport jobs;
    private long cursor;
    public LegacyCouponUsageRepairJob(LegacyCouponUsageRepairService repair,JobSupport jobs) { this.repair=repair;this.jobs=jobs; }
    @Override public String name() { return "legacy-coupon-usage-repair"; }
    @Scheduled(cron="${shop.job.legacy-coupon-usage-repair.cron:10 */2 * * * *}")
    @SchedulerLock(name="legacy-coupon-usage-repair",lockAtMostFor="PT90S")
    public void tick() { jobs.run(name(),()->run(null).detail()); }
    @Bean public JobDeclaration legacyCouponUsageRepairDeclaration() {
        return new JobDeclaration(name(),"旧券消费记录修复","只补原核销投影，不改余额、不重发历史通知",
                "pay-svc","10 */2 * * * *",true,60,90,true,true);
    }
    @Override public synchronized JobResult run(JobInvocation invocation) {
        var context=new EntitlementContext("MAIN","LEGACY_USAGE_REPAIR",name(),EntitlementRecords.digest(name()));
        var result=repair.repair(context,cursor,100);cursor=result.nextId();
        String detail="扫描%d，补齐%d，失败%d".formatted(result.scanned(),result.repaired(),result.failed());
        return result.failed()>0?JobResult.failed(detail,"LEGACY_USAGE_REPAIR_PENDING"):JobResult.ok(detail);
    }
}
