package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import java.util.EnumMap;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cursor rotation prevents a poison operation from monopolizing a batch; durable outcomes survive restarts. */
@Component
@ConditionalOnProperty(name="shop.job.enabled",havingValue="true")
public class EntitlementRecoveryJob implements JobHandler {
    private final EntitlementRecoveryService recovery;
    private final JobSupport jobs;
    private final EnumMap<Kind,Long> cursors=new EnumMap<>(Kind.class);
    public EntitlementRecoveryJob(EntitlementRecoveryService recovery,JobSupport jobs) {
        this.recovery=recovery; this.jobs=jobs;
    }
    @Override public String name() { return "entitlement-recovery"; }
    @Scheduled(cron="${shop.job.entitlement-recovery.cron:30 */2 * * * *}")
    @SchedulerLock(name="entitlement-recovery",lockAtMostFor="PT90S")
    public void tick() { jobs.run(name(),() -> run(null).detail()); }
    @Bean public JobDeclaration entitlementRecoveryDeclaration() {
        return new JobDeclaration(name(),"权益及储值在途恢复","按原号恢复，未知保留；不自动实收或超时释放",
                "shop-app","30 */2 * * * *",true,90,120,true,true);
    }
    @Override public synchronized JobResult run(JobInvocation invocation) {
        int scanned=0,completed=0,deferred=0,failed=0;
        for(Kind kind:Kind.values()) {
            var result=recovery.recover("MAIN",kind,cursors.getOrDefault(kind,0L),100);
            cursors.put(kind,result.nextId());
            scanned+=result.scanned(); completed+=result.completed(); deferred+=result.deferred(); failed+=result.failed();
        }
        String detail="扫描%d，完成%d，待查%d，失败%d".formatted(scanned,completed,deferred,failed);
        return failed>0?JobResult.failed(detail,"ENTITLEMENT_RECOVERY_PENDING"):JobResult.ok(detail);
    }
}
