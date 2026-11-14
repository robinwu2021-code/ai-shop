package ai.neargo.shop.message.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.message.MessageService;
import java.time.LocalDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 商户回报的诊断资料保留 90 天（TDD-商户App问题回报与版本 已拍板 P7）：到期清空 {@code notify_ticket.diagnostics}，
 * 工单本身（分类、描述、回复）保留。诊断里的请求路径虽已脱敏，长期留存仍没有必要。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class TicketDiagnosticsPurgeJob implements JobHandler {

    static final int RETENTION_DAYS = 90;

    private final MessageService messages;
    private final JobSupport jobs;

    public TicketDiagnosticsPurgeJob(MessageService messages, JobSupport jobs) {
        this.messages = messages;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.ticket-diagnostics-purge.cron:0 40 3 * * *}")
    @SchedulerLock(name = "ticket-diagnostics-purge", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void tick() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Override
    public String name() {
        return "ticket-diagnostics-purge";
    }

    @Bean
    public JobDeclaration ticketDiagnosticsPurgeDeclaration() {
        return new JobDeclaration(name(), "回报诊断资料清理", "清空 90 天前商户回报的诊断资料，工单保留",
                "shop-core", "0 40 3 * * *", true, 240, 300, true, false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = messages.purgeTicketDiagnostics(LocalDateTime.now().minusDays(RETENTION_DAYS));
        return JobResult.ok(n == 0 ? null : "清空诊断资料 " + n + " 张");
    }
}
