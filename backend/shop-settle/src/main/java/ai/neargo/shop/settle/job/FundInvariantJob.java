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
 * 资金不变式巡检（I1 / I2 / I3）—— <b>三层保证里的第三层</b>。
 *
 * <p>它不看消息、不看重试次数，只看事实：「这个子单是已支付的，它有没有结算单？」
 * Outbox 投递失败、消费者有 bug、甚至 Outbox 那一行本身没写成功，都躲不过它。
 * 详见 {@link FundInvariantService} 的类注释。
 *
 * <h2>两条的处置方式刻意不同</h2>
 * <ul>
 *   <li><b>I1 自动补</b> —— 补生成结算单是幂等且只增不减的动作，自动执行安全；</li>
 *   <li><b>I2 只告警</b> —— 删账不可逆，而成因不止一种（含「巡检自己算错了窗口」）。</li>
 *   <li><b>I3 清标记</b> —— 标记说发过积分而没有流水时，把标记改回未发。
 *       方向安全：用户一分没拿到，而 {@code grantOnPay} 的幂等原本就是靠这个标记，
 *       不清掉它永远重发不了。<b>反方向（有流水而标记为假）刻意不动</b> ——
 *       那说明多发了一次，补标记等于把它盖掉，而分还在用户手里。</li>
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

    /**
     * 预占的积分多久没等到订单就算死。
     *
     * <p>给 15 分钟不是保守，是**避开正常链路**：下单与扣分在同一个请求里，
     * 正常情况下秒级就有订单。但收银台上还有「等支付」那一段 ——
     * 那种单的状态是 WAIT_PAY，判据里已经把它排除了，所以这个数只要覆盖
     * 「下单事务本身」的耗时。太短会误伤正在建的单。
     */
    @Value("${shop.job.fund-invariant.hold-minutes:15}")
    private int holdMinutes;

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
                "比对「已支付的单」与「结算单」两边：缺的自动补出来，多出来的只报不动（删账不可逆）；"
                        + "顺带查「标记说发过积分而没有流水」，把标记清掉让下一轮重发",
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
            log.warn("[fund-invariant] **I1–I3 一行都没扫到**（回看 {} 小时）—— "
                    + "这与「没有违反」长得一样，但通常意味着查询条件或时间窗有问题",
                    lookbackHours);
            /*
             * **不在这里 return。** I6 走的是另一个时间窗（分钟级），
             * 提前返回会让「I1–I3 没数据」把 I6 一起带走 ——
             * 而 I6 释放的是用户已经被扣走的分，最不该被别的检查的空转连累。
             */
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

        /*
         * I6 与上面三条分开跑，因为**时间窗不同**：I1–I3 回看 26 小时，
         * 而预占的积分只需要等几分钟就能判死 —— 订单落库是同一个请求里的事。
         * 用同一个窗口的话，刚回滚的那批要等一整天才还给用户。
         */
        FundInvariantService.ReleaseResult rel = invariants.releaseDeadHolds(
                System.currentTimeMillis() - holdMinutes * 60_000L, limit);
        if (rel.dead() > 0) {
            log.warn("[fund-invariant] I6 释放预占积分 {}/{}（扫 {} 条）",
                    rel.released(), rel.dead(), rel.scanned());
        }

        if (r.grantedNoLedger() > 0) {
            log.error("[fund-invariant] I3 违反 {} 条：标记说发过积分而没有流水，"
                    + "已清标记 {} 行 —— 下一轮会重发", r.grantedNoLedger(), r.clearedFlags());
        }

        return JobResult.ok(("已付子单 %d（缺 %d · 补 %d）· 结算单 %d（对不上 %d）"
                + "· 已发分 %d（无流水 %d · 清 %d）· 预占 %d（已死 %d · 释放 %d）")
                .formatted(r.scannedPaid(), r.missingBill(), r.repairedBill(),
                        r.scannedBills(), r.orphanBill(),
                        r.scannedGranted(), r.grantedNoLedger(), r.clearedFlags(),
                        rel.scanned(), rel.dead(), rel.released()));
    }
}
