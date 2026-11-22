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
import java.time.Duration;
import java.time.Instant;

/**
 * 发票上传：台湾 B2C 开立后 48 小时内必须上传，每分钟扫一次到期任务。
 *
 * <p>开关 {@code shop.invoice.transmission.enabled} 默认关：正式通道签约前，桩通道的「已送达」没有法律意义，
 * 默认不跑可以避免把一批 STUB 回执误当成已上传。
 */
@ConditionalOnProperty(name = "shop.invoice.transmission.enabled", havingValue = "true")
@Component
public class InvoiceTransmissionJob implements JobHandler {

    private final InvoiceTransmissionService transmissions;
    private final JobSupport jobs;

    @Value("${shop.invoice.transmission.limit:200}")
    private int limit;

    /** 注销进度查询间隔、注销中告警阈值、期末前未重开提醒提前量（TDD-发票-注销与同号重开 §4） */
    @Value("${shop.invoice.revoke.check-interval-minutes:10}")
    private int revokeCheckMinutes;

    @Value("${shop.invoice.revoke.pending-alert-hours:24}")
    private int revokePendingAlertHours;

    @Value("${shop.invoice.revoke.reissue-alert-days:5}")
    private int reissueAlertDays;

    public InvoiceTransmissionJob(InvoiceTransmissionService transmissions, JobSupport jobs) {
        this.transmissions = transmissions;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.invoice.transmission.cron:15 * * * * *}")
    @SchedulerLock(name = "invoice-transmission", lockAtLeastFor = "PT10S", lockAtMostFor = "PT5M")
    public void scan() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Override
    public String name() {
        return "invoice-transmission";
    }

    @Bean
    public JobDeclaration invoiceTransmissionDeclaration() {
        return new JobDeclaration(name(), "发票上传",
                "把开立、作废、折让、注销的发票报文送到开票通道；失败按退避重试同一报文，不重新取号；查注销进度",
                "invoice-domain", "15 * * * * *", true, 240, 300, true, false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int sent = transmissions.transmitDue(limit);
        int[] r = transmissions.checkRevocations(Instant.now(), Duration.ofMinutes(revokeCheckMinutes),
                Duration.ofHours(revokePendingAlertHours), Duration.ofDays(reissueAlertDays));
        return JobResult.ok("sent=" + sent + " revoked=" + r[0] + " revokeFailed=" + r[1] + " revokeAlerts=" + r[2]);
    }
}
