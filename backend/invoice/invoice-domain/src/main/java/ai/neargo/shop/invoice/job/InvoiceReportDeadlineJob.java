package ai.neargo.shop.invoice.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.invoice.service.InvoiceTransmissionService;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发票上报期限巡检（TDD-发票模块 §14）：台湾 B2C 开立 / 作废 / 折让后 48 小时内必须上传（B2B 7 日），
 * 每 10 分钟扫一次未送达的任务 —— 过半预警（{@code INVOICE_REPORT_DEADLINE_WARN}）、过期告警（{@code INVOICE_REPORT_OVERDUE}），
 * 每条每级只报一次。
 *
 * <p>与上传任务同一个开关 {@code shop.invoice.transmission.enabled}：上传没开时任务本来就不会送达，告警没有意义。
 */
@ConditionalOnProperty(name = "shop.invoice.transmission.enabled", havingValue = "true")
@Component
public class InvoiceReportDeadlineJob implements JobHandler {

    private final InvoiceTransmissionService transmissions;
    private final JobSupport jobs;

    @Value("${shop.invoice.deadline.limit:500}")
    private int limit;

    public InvoiceReportDeadlineJob(InvoiceTransmissionService transmissions, JobSupport jobs) {
        this.transmissions = transmissions;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.invoice.deadline.cron:40 */10 * * * *}")
    @SchedulerLock(name = "invoice-report-deadline", lockAtLeastFor = "PT10S", lockAtMostFor = "PT5M")
    public void scan() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Override
    public String name() {
        return "invoice-report-deadline";
    }

    @Bean
    public JobDeclaration invoiceReportDeadlineDeclaration() {
        return new JobDeclaration(name(), "发票上报期限巡检",
                "未送达的上传任务：到期限一半预警、过了法定期限告警（台湾 B2C 48 小时、B2B 7 日），每条每级只报一次",
                "invoice-domain", "40 */10 * * * *", true, 240, 300, true, false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int[] r = transmissions.checkDeadlines(limit);
        return JobResult.ok("warned=" + r[0] + " overdue=" + r[1]);
    }
}
