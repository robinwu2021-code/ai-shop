package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;

import java.time.Duration;
import java.time.Instant;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cash-bill and settlement-batch consistency; points have a separate recovery task. */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class FundInvariantJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(FundInvariantJob.class);

    /**
     * 「I1–I2 一行都没扫到」持续期间，隔多久重述一次。
     *
     * <p>这条轴一小时一轮，此前每轮原样打一条 —— 而它报的是「查询条件/时间窗可能写错了」，
     * 那种事一旦成立就会一直成立，于是一天 24 条一模一样的 WARN。
     * 条数远不如对账那条多（见 {@link StuckStateLog}），但毛病是同一个：
     * 看的人分不出「这是今天早上开始的，还是上周就这样了」。
     *
     * <p><b>为什么是 6 小时而不是 1 小时</b>：1 小时对一小时一轮的任务等于没有抑制。
     * 6 小时 = 一天 4 条，既压下了重复，又保证<b>一个工作日之内至少重述一次</b>，
     * 不至于早上来看不到任何还在坏着的迹象。
     */
    private static final Duration RESTATE_EVERY = Duration.ofHours(6);

    /** 只有一件事要节流，key 用固定串。见 {@link StuckStateLog} 的类注释。 */
    private static final String K_NOTHING_SCANNED = "I1-I2-nothing-scanned";

    private final StuckStateLog stuck = new StuckStateLog(RESTATE_EVERY);

    private final FundInvariantReconciler invariants;
    private final JobSupport jobs;
    /** R6 的核验在 pay 侧（纯 pay 内部的账，不跨域）—— 这里只负责让它每轮跑一次 */
    private final ai.neargo.shop.pay.SettleBatchService batches;

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

    public FundInvariantJob(FundInvariantReconciler invariants, JobSupport jobs,
                            ai.neargo.shop.pay.SettleBatchService batches) {
        this.batches = batches;
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
                        + "积分源事件、占用与退款由独立积分恢复任务处理",
                "shop-app", "0 20 * * * *", true,
                2700, 3000, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        long since = System.currentTimeMillis() - lookbackHours * 3_600_000L;
        FundInvariantReconciler.Result r = invariants.scan(since, limit);

        reportNothingScanned(r.scannedAnything(), Instant.now());

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
         * **R6：批次合计 ≡ 其下结算单之和**（S10 · 2026-09-02）。
         *
         * 与 I1–I2 一起跑而不是单开一个任务：它们都是「资金账对不对得上」，
         * 分散在多个任务里的话，运营要看几个地方才知道账有没有问题。
         *
         * 这一条<b>只报不改</b> —— 合计是放款依据，而巡检自己也可能算错。
         */
        var mismatches = batches.checkBatchTotals(limit);
        if (!mismatches.isEmpty()) {
            log.error("[fund-invariant] **R6 违反 {} 条**：批次合计与结算单之和对不上 —— "
                    + "放款按合计走，这个数错了就是给商家打错钱。**不自动改**，逐条查",
                    mismatches.size());
        }

        return JobResult.ok(("已付子单 %d（缺 %d · 补 %d）· 结算单 %d（对不上 %d）· 批次合计不符 %d")
                .formatted(r.scannedPaid(), r.missingBill(), r.repairedBill(),
                        r.scannedBills(), r.orphanBill(), mismatches.size()));
    }

    /**
     * 报「I1–I2 一行都没扫到」——<b>对照量先判</b>，且<b>按状态变化报、不按轮次报</b>。
     *
     * <h2>为什么要单独报这件事</h2>
     * 「违反 0 条」与「一行都没扫到」在结果上一模一样，而后者才是最该红的那种：
     * 查询条件写错、索引没走上、时间窗算反。混进「一切正常」里就没人看得见了。
     *
     * <h2>为什么不每轮都打</h2>
     * 它一旦成立就会一直成立（写错的查询不会自己好），而这条轴一小时一轮 ——
     * 于是一天 24 条一模一样的 WARN。条数不如对账那条多，毛病是同一个：
     * 看的人分不出「这是今天早上开始的，还是上周就这样了」。节奏见 {@link StuckStateLog}。
     *
     * <p><b>好了也要留一条。</b> 此前没有这条 INFO：扫得动了只能靠「WARN 不再出现」去反推，
     * 而那与「任务挂了、压根没跑」长得一模一样。
     *
     * <p><b>刻意不做的：不降级、不静音。</b> 第一次原级别原速打出去，一刻不推迟。
     *
     * @param scannedAnything 这一轮 I1–I2 的对照量是不是非 0
     * @param now             传进来而不是内部取，便于测试推进时间
     */
    void reportNothingScanned(boolean scannedAnything, Instant now) {
        if (!scannedAnything) {
            StuckStateLog.State st = stuck.stillBad(K_NOTHING_SCANNED, now);
            if (st == null) {
                return;   // 还在重述间隔内：这一轮不说话
            }
            if (st.first()) {
                log.warn("[fund-invariant] **I1–I2 一行都没扫到**（回看 {} 小时）—— "
                        + "这与「没有违反」长得一样，但通常意味着查询条件或时间窗有问题",
                        lookbackHours);
            } else {
                log.warn("[fund-invariant] **I1–I2 仍然一行都没扫到**：已持续 {} 轮 / {} 分钟"
                        + "（自 {} 起，回看 {} 小时）—— 查询条件或时间窗有问题",
                        st.rounds(), st.minutes(), st.since(), lookbackHours);
            }
        } else {
            StuckStateLog.State ok = stuck.recovered(K_NOTHING_SCANNED, now);
            if (ok != null) {
                log.info("[fund-invariant] I1–I2 又扫得到数据了（此前连续 {} 轮 / {} 分钟一行都没扫到，自 {} 起）",
                        ok.rounds(), ok.minutes(), ok.since());
            }
        }
    }
}
