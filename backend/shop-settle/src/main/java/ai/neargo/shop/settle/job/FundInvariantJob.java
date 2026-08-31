package ai.neargo.shop.settle.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.settle.service.FundInvariantService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资金不变式巡检（I1 / I2）—— <b>三层保证里的第三层</b>。
 *
 * <p>它不看消息、不看重试次数，只看事实：「这个子单是已支付的，它有没有结算单？」
 * Outbox 投递失败、消费者有 bug、甚至 Outbox 那一行本身没写成功，都躲不过它。
 * 详见 {@link FundInvariantService} 的类注释。
 *
 * <h2>两条的处置方式刻意不同</h2>
 * <ul>
 *   <li><b>I1 自动补</b> —— 补生成结算单是幂等且只增不减的动作，自动执行安全；</li>
 *   <li><b>I2 只告警</b> —— 删账不可逆，而成因不止一种（含「巡检自己算错了窗口」）。</li>
 * </ul>
 *
 * <p>凡是「自动修复」的，都要能证明它幂等且方向安全。证不了的就只报不动。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class FundInvariantJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(FundInvariantJob.class);

    private final FundInvariantService invariants;
    private final JobSupport jobs;

    /**
     * 回看窗口。**不扫全量** —— 全量一次要几分钟，而这个任务每小时跑一次。
     *
     * <p>给 26 小时而不是 1 小时：任务本身可能停摆几轮（发版、锁没释放、worker 挂了），
     * 窗口只留一小时的话，停摆期间支付的那些单<b>再也不会被扫到</b> ——
     * 而那正是最可能出问题的一段时间。
     */
    @Value("${shop.job.fund-invariant.lookback-hours:26}")
    private int lookbackHours;

    @Value("${shop.job.fund-invariant.limit:2000}")
    private int limit;

    /** 缺结算单超过这个时长就升级为 error —— 秒级窗口是设计，小时级就是故障 */
    @Value("${shop.job.fund-invariant.alert-after-minutes:60}")
    private int alertAfterMinutes;

    public FundInvariantJob(FundInvariantService invariants, JobSupport jobs) {
        this.invariants = invariants;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.fund-invariant.cron:0 20 * * * *}")
    @SchedulerLock(name = "fund-invariant", lockAtLeastFor = "PT1M", lockAtMostFor = "PT50M")
    public void scan() {
        jobs.run("fund-invariant", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "fund-invariant";
    }

    @Bean
    public JobDeclaration fundInvariantDeclaration() {
        return new JobDeclaration("fund-invariant", "资金不变式巡检",
                "比对「已支付的单」与「结算单」两边：缺的自动补出来，多出来的只报不动（删账不可逆）",
                "shop-settle", "0 20 * * * *", true,
                2700, 3000, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        long since = System.currentTimeMillis() - lookbackHours * 3_600_000L;
        FundInvariantService.ScanResult r = invariants.scan(since, limit);

        /*
         * **对照量先判。**「违反 0 条」与「一行都没扫到」在结果上一模一样，
         * 而后者才是最该红的那种：查询条件写错、索引没走上、时间窗算反。
         * 这里把它单独报出来，而不是混进「一切正常」。
         */
        if (!r.scannedAnything()) {
            log.warn("[fund-invariant] **一行都没扫到**（回看 {} 小时）—— "
                    + "这与「没有违反」长得一样，但通常意味着查询条件或时间窗有问题",
                    lookbackHours);
            return JobResult.ok("一行都没扫到（回看 %d 小时）".formatted(lookbackHours));
        }

        if (r.orphanBill() > 0) {
            // I2 已在 Service 里打过 error；这里只保证它出现在任务详情里，运营看得见
            log.error("[fund-invariant] I2 违反 {} 条 —— 需人工判断", r.orphanBill());
        }
        if (r.missingBill() > 0) {
            long lagMinutes = r.oldestMissingAt() > 0
                    ? (System.currentTimeMillis() - r.oldestMissingAt()) / 60_000L : 0L;
            if (lagMinutes > alertAfterMinutes) {
                log.error("[fund-invariant] **有单已支付 {} 分钟仍无结算单**（补了 {} 张）—— "
                                + "秒级窗口是设计，小时级是故障：查 Outbox 投递链路",
                        lagMinutes, r.repairedBill());
            } else {
                log.warn("[fund-invariant] 补生成 {} 张结算单（最久滞后 {} 分钟）",
                        r.repairedBill(), lagMinutes);
            }
        }

        return JobResult.ok("已付子单 %d（缺 %d · 补 %d）· 结算单 %d（对不上 %d）"
                .formatted(r.scannedPaid(), r.missingBill(), r.repairedBill(),
                        r.scannedBills(), r.orphanBill()));
    }
}
