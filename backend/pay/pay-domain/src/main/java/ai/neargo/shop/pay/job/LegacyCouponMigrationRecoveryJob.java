package ai.neargo.shop.pay.job;
import ai.neargo.job.api.*;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationRecoveryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Bounded roll-forward only; this job cannot create a core source commitment or reopen a legacy source. */
@Component
@ConditionalOnProperty(name={"shop.job.enabled","shop.pay.entitlement.migration-recovery.enabled"},havingValue="true")
public class LegacyCouponMigrationRecoveryJob implements JobHandler {
    private final LegacyCouponMigrationRecoveryService recovery;
    private final JobSupport jobs;
    private long cursor;
    public LegacyCouponMigrationRecoveryJob(LegacyCouponMigrationRecoveryService recovery,JobSupport jobs) {this.recovery=recovery;this.jobs=jobs;}
    @Override public String name() {return "legacy-coupon-migration-recovery";}
    @Scheduled(cron="${shop.job.legacy-coupon-migration-recovery.cron:15 */5 * * * *}")
    @SchedulerLock(name="legacy-coupon-migration-recovery",lockAtMostFor="PT30M")
    public void tick() {jobs.run(name(),()->run(null).detail());}
    @Bean public JobDeclaration legacyCouponMigrationRecoveryDeclaration() {
        return new JobDeclaration(name(),"旧券已提交迁移恢复","只按原固定计划恢复，源未知保留，不自动源提交", "pay-svc","15 */5 * * * *",true,1500,1800,true,true);
    }
    @Override public synchronized JobResult run(JobInvocation invocation) {
        var result=recovery.recover("MAIN",cursor,10);cursor=result.nextId();
        String detail="扫描%d，完成%d，待确认%d，失败%d".formatted(result.scanned(),result.completed(),result.deferred(),result.failed());
        return result.failed()>0?JobResult.failed(detail,"MIGRATION_RECOVERY_PENDING"):JobResult.ok(detail);
    }
}
