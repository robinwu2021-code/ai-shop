package ai.neargo.shop.pay.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.notify.service.BizNotifyService;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 投递到期的 webhook 答复（TDD-支付服务-I5接入层 S6）：每 15 秒一轮，一轮最多 200 条；租约防多实例重投 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class BizNotifyDeliveryJob implements JobHandler {

    private final BizNotifyService notifier;
    private final JobSupport jobs;

    public BizNotifyDeliveryJob(BizNotifyService notifier, JobSupport jobs) {
        this.notifier = notifier;
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return "pay-biz-notify-delivery";
    }

    @Scheduled(cron = "${shop.job.pay-biz-notify-delivery.cron:*/15 * * * * *}")
    @SchedulerLock(name = "pay-biz-notify-delivery", lockAtMostFor = "PT5M")
    public void tick() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Bean
    public JobDeclaration payBizNotifyDeliveryDeclaration() {
        return new JobDeclaration(name(), "支付结果 webhook 投递", "到期的答复逐条签名投递，失败退避，超 24 小时放弃",
                "pay-svc", "*/15 * * * * *", true, 300, 300, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        return JobResult.ok("投递 %d 条".formatted(notifier.deliverDue(Instant.now(), 200)));
    }
}
