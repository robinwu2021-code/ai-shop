package ai.neargo.shop.pay.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.acquiring.service.PayExceptionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 关单后到账 / 重复支付自动原路退回（TDD-支付服务-W3收单补完 E04）。默认关：先人工处置，跑稳了再开 */
@Component
@ConditionalOnProperty(name = {"shop.job.enabled", "shop.pay.late-payment.auto-refund"}, havingValue = "true")
public class PayExceptionAutoRefundJob implements JobHandler {

    private final PayExceptionService exceptions;
    private final JobSupport jobs;

    public PayExceptionAutoRefundJob(PayExceptionService exceptions, JobSupport jobs) {
        this.exceptions = exceptions;
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return "pay-exception-auto-refund";
    }

    @Scheduled(cron = "${shop.job.pay-exception-auto-refund.cron:30 */10 * * * *}")
    @SchedulerLock(name = "pay-exception-auto-refund", lockAtMostFor = "PT9M")
    public void tick() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Bean
    public JobDeclaration payExceptionAutoRefundDeclaration() {
        return new JobDeclaration(name(), "支付异常自动原路退回", "关单后到账与重复支付逐条原路退回；退款执行器幂等",
                "pay-svc", "30 */10 * * * *", true, 540, 540, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        return JobResult.ok("发起 %d 笔".formatted(exceptions.autoRefundOpen(50)));
    }
}
