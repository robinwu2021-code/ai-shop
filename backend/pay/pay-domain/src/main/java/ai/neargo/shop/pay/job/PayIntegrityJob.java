package ai.neargo.shop.pay.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.acquiring.backfill.PayIntegrity;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付账巡检（TDD-支付服务-W3收单补完 E09）：凭证借贷平衡（Q7）、近一天没挂支付单的尝试（Q5）、恒等式①。
 * 任一项不为 0 记失败 —— 作业中心的失败告警就是这一层的出口；只看事实、不修。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class PayIntegrityJob implements JobHandler {

    private final PayIntegrity integrity;
    private final JobSupport jobs;

    public PayIntegrityJob(PayIntegrity integrity, JobSupport jobs) {
        this.integrity = integrity;
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return "pay-integrity";
    }

    @Scheduled(cron = "${shop.job.pay-integrity.cron:0 20 * * * *}")
    @SchedulerLock(name = "pay-integrity", lockAtMostFor = "PT30M")
    public void tick() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Bean
    public JobDeclaration payIntegrityDeclaration() {
        return new JobDeclaration(name(), "支付账巡检", "凭证借贷平衡、尝试挂单、已付减已退等于流水净额；不平即告警",
                "pay-svc", "0 20 * * * *", true, 1800, 1800, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        long unbalanced = integrity.unbalancedVouchers();
        long unlinked = integrity.unlinkedAttempts(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        List<String> mismatched = integrity.ledgerMismatchedOrders(20);
        String detail = "借贷不平凭证 %d，未挂单尝试 %d，流水对不上的支付单 %d%s".formatted(unbalanced, unlinked,
                mismatched.size(), mismatched.isEmpty() ? "" : "（如 " + String.join(",", mismatched) + "）");
        return unbalanced == 0 && unlinked == 0 && mismatched.isEmpty() ? JobResult.ok(detail)
                : JobResult.failed(detail, "PAY_INTEGRITY_BROKEN");
    }
}
