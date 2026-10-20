package ai.neargo.shop.pay.job;
import ai.neargo.job.api.*;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssueRecoveryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name={"shop.job.enabled","shop.pay.entitlement.legacy-coupon.issue-recovery-enabled"},havingValue="true")
public class LegacyCouponIssueRecoveryJob implements JobHandler {
    private final LegacyCouponIssueRecoveryService recovery;private final JobSupport jobs;private long cursor;
    public LegacyCouponIssueRecoveryJob(LegacyCouponIssueRecoveryService recovery,JobSupport jobs) {this.recovery=recovery;this.jobs=jobs;}
    public String name() {return "legacy-coupon-issue-recovery";}
    @Scheduled(cron="${shop.job.legacy-coupon-issue-recovery.cron:15 */3 * * * *}")
    @SchedulerLock(name="legacy-coupon-issue-recovery",lockAtMostFor="PT6M")
    public void tick() {jobs.run(name(),()->run(null).detail());}
    @Bean public JobDeclaration legacyCouponIssueRecoveryDeclaration() {
        return new JobDeclaration(name(),"旧券入口已接受发行恢复","只续原生批次，未知保留，不重新选人或发放", "pay-svc","15 */3 * * * *",true,300,360,true,true);
    }
    public synchronized JobResult run(JobInvocation invocation) {
        var result=recovery.recover("MAIN",cursor,20);cursor=result.nextId();
        String detail="扫描%d，完成%d，待处理%d，失败%d".formatted(result.scanned(),result.completed(),result.deferred(),result.failed());
        return result.failed()>0?JobResult.failed(detail,"LEGACY_ISSUE_RECOVERY_PENDING"):JobResult.ok(detail);
    }
}
