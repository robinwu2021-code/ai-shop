package ai.neargo.shop.paybridge;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Bounded keyset scans rotate past failures; durable acknowledgements survive process restarts. */
@Component
@ConditionalOnProperty(name="shop.job.enabled",havingValue="true")
public class CardSaleRecoveryJob implements JobHandler {
    private final CardSaleRecoveryService recovery;
    private final JobSupport jobs;
    private String cursor="";
    public CardSaleRecoveryJob(CardSaleRecoveryService recovery,JobSupport jobs) {
        this.recovery=recovery; this.jobs=jobs;
    }
    @Override public String name() { return "card-sale-recovery"; }
    @Scheduled(cron="${shop.job.card-sale-recovery.cron:0 */5 * * * *}")
    @SchedulerLock(name="card-sale-recovery",lockAtMostFor="PT4M")
    public void scan() { jobs.run(name(),() -> run(null).detail()); }
    @Bean public JobDeclaration cardSaleRecoveryDeclaration() {
        return new JobDeclaration(name(),"售卡发放与取消恢复","按原业务号重试终态订单，不自动取消或退款",
                "shop-app","0 */5 * * * *",true,240,300,true,true);
    }
    @Override public synchronized JobResult run(JobInvocation invocation) {
        var ctx=new EntitlementContext("MAIN","RECOVERY",name(),"0".repeat(64));
        var result=recovery.recover(ctx,cursor,100);
        cursor=result.nextOrderNo();
        String detail="扫描%d，完成%d，失败%d".formatted(result.scanned(),result.completed(),result.failed());
        return result.failed()>0 ? JobResult.failed(detail,"SALE_RECOVERY_PENDING") : JobResult.ok(detail);
    }
}
