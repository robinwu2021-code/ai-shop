package ai.neargo.shop.invoice.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.invoice.service.InvoiceSetupService;
import ai.neargo.shop.job.JobSupport;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 字轨号段水位巡检（TDD-发票模块 §15）：本期号码不足 / 用尽、期末前下期未备时提醒，开了自动领号的向加值中心领号。
 * 号码用尽时开票直接失败（INVOICE_NUMBER_EXHAUSTED），所以要在用尽之前发现。
 *
 * <p>开关 {@code shop.invoice.range-watch.enabled} 默认关，正式开票前打开；期末提前量 {@code shop.invoice.range-watch.prefetch-days}（默认 5 天）。
 */
@ConditionalOnProperty(name = "shop.invoice.range-watch.enabled", havingValue = "true")
@Component
public class InvoiceRangeWatermarkJob implements JobHandler {

    private final InvoiceSetupService setup;
    private final JobSupport jobs;

    @Value("${shop.invoice.range-watch.prefetch-days:5}")
    private int prefetchDays;

    public InvoiceRangeWatermarkJob(InvoiceSetupService setup, JobSupport jobs) {
        this.setup = setup;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.invoice.range-watch.cron:20 5/30 * * * *}")
    @SchedulerLock(name = "invoice-range-watermark", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void scan() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Override
    public String name() {
        return "invoice-range-watermark";
    }

    @Bean
    public JobDeclaration invoiceRangeWatermarkDeclaration() {
        return new JobDeclaration(name(), "发票号段水位巡检",
                "本期剩余号码低于提醒值或用尽时提醒；期末前下期还没号段时提醒；开了自动领号的向加值中心领号；"
                        + "上一期未用号码向支持空白字轨的加值中心上报（汎宇 E0402）",
                "invoice-domain", "20 5/30 * * * *", true, 540, 600, true, false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        var now = Instant.now();
        int[] r = setup.watchRanges(now, Duration.ofDays(prefetchDays));
        int blank = setup.reportBlankRanges(now);
        return JobResult.ok("alerts=" + r[0] + " fetched=" + r[1] + " blank=" + blank);
    }
}
