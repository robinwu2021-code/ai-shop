package ai.neargo.shop.merchant.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.merchant.service.MerchantPaymentService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 进件状态轮询：主动去通道问「批了没有」。
 *
 * <p><b>为什么必须有它</b>：今天进件状态只有商家自己点「刷新」才会推进 ——
 * 没有回调、没有轮询。商家不点，单子就一直显示「审核中」，
 * 而通道那边可能三天前就批了。这不是体验问题：<b>他会以为平台没在办</b>，
 * 而在那期间他一分钱都收不了。
 *
 * <p><b>轮询是过渡形态。</b>通道的进件结果回调接上之后，这个任务应该降频而不是删掉 ——
 * 回调会丢，而「回调丢了就永远卡住」正是这条链路今天的样子。
 *
 * <p><b>只在 worker 部署跑</b>：多实例各跑一遍时同一单会被查两次。
 * 查单本身无害（{@code refresh} 幂等），但会把通道的查询配额白白翻倍。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class ApplymentPollJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ApplymentPollJob.class);

    private final MerchantPaymentService paymentService;
    private final JobSupport jobs;

    /** 一轮最多查多少条 —— 第一次上线时库里可能积着一批历史 APPLYING，别一口气把通道打满 */
    @Value("${shop.job.applyment-poll.limit:200}")
    private int limit;

    /**
     * 超过多少天还没结果就单独计数并告警。
     *
     * <p>通道审核一般一两天。卡更久说明<b>这一单需要人去问</b>，
     * 而不是继续等下一轮 —— 而没有这个计数的话，一单卡三个月也没有任何地方会说。
     */
    @Value("${shop.job.applyment-poll.stale-days:3}")
    private int staleDays;

    public ApplymentPollJob(MerchantPaymentService paymentService, JobSupport jobs) {
        this.paymentService = paymentService;
        this.jobs = jobs;
    }

    /**
     * 每 30 分钟一轮。
     *
     * <p>频率的依据是<b>商家的等待感</b>，不是通道的处理速度：
     * 半小时内看到状态变化，他不会觉得平台没动静。
     * 更密没有意义（通道那边以小时计），更疏则那半天里他会来问客服。
     */
    @Scheduled(cron = "${shop.job.applyment-poll.cron:0 */30 * * * *}")
    @SchedulerLock(name = "applyment-poll", lockAtLeastFor = "PT1M", lockAtMostFor = "PT25M")
    public void scan() {
        jobs.run("applyment-poll", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "applyment-poll";
    }

    @Bean
    public JobDeclaration applymentpollDeclaration() {
        return new JobDeclaration("applyment-poll", "进件状态轮询",
                "主动去通道问进件批了没有。不跑的话，商家不点「刷新」就一直显示「审核中」，"
                        + "而通道可能早就批了 —— 那期间他一分钱都收不了",
                "shop-merchant", "0 */30 * * * *", true,
                // 锁 25 分钟小于 30 分钟间隔：真卡死时下一轮能接手。
                // 超时 20 分钟，留 5 分钟给锁兜底 —— 两者差太多的话，
                // 还在跑的任务会被记成 TIMEOUT
                1200, 1500, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        MerchantPaymentService.PollResult r =
                paymentService.pollApplying(limit, staleDays * 86400000L);
        if (r.scanned() == 0) {
            // detail 保持 null：JobSupport 用它区分「跑了但没事」与「跑了并做了事」
            return JobResult.ok(null);
        }
        /*
         * 两种情况打 WARN，而它们的含义完全不同：
         *   failed 有值 —— 我方或通道出了问题（凭据、网络、接口变更）
         *   stale  有值 —— 通道那边卡住了，要有人去问
         * 合成一条「有异常」的话，值班的人不知道该查哪一头。
         */
        if (r.failed() > 0) {
            log.warn("[applyment-poll] 查了 {} 条，{} 条查询本身失败 —— 先看凭据与通道可用性",
                    r.scanned(), r.failed());
        }
        if (r.stale() > 0) {
            log.warn("[applyment-poll] {} 条进件超过 {} 天仍无结果 —— 需要人去问通道",
                    r.stale(), staleDays);
        }
        if (r.settled() > 0) {
            log.info("[applyment-poll] {} 条出结果（共查 {} 条）", r.settled(), r.scanned());
        }
        return JobResult.ok("查 " + r.scanned() + " / 出结果 " + r.settled()
                + " / 失败 " + r.failed() + " / 超期 " + r.stale());
    }
}
