package ai.neargo.shop.trade.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Durable approved refunds are selected fairly and resumed outside database transactions.
 * Each item commits preparation, money evidence, and business completion in their owning transactions.
 * One blocked points decision cannot stop the rest of the batch. Unreversed completed refunds remain alerts.
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class RefundRetryJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryJob.class);

    /** 系统重试的操作人。留痕里要能一眼看出这笔不是人点的 */
    private static final String SYSTEM = "SYSTEM";

    private final AfterSaleService afterSaleService;
    private final JobSupport jobs;

    /**
     * 进 {@code REFUNDING} 后多久才算「卡住」。
     *
     * <p>给 30 分钟不是保守，是<b>避开正常链路</b>：刚进 REFUNDING 的单
     * 可能正被同步流程处理，这时插进去会和它撞在同一笔上。
     */
    @Value("${shop.job.refund-retry.stuck-minutes:30}")
    private int stuckMinutes;

    /**
     * I4 的回看窗口。
     *
     * <p>比续跑的窗口长得多（天 vs 分钟），因为它不是要处理什么 ——
     * 它是<b>让人看见一个本该为零的数</b>。窗口太短的话，
     * 昨天出的那一条今天就不见了，而它并没有被解决。
     */
    @Value("${shop.job.refund-retry.i4-lookback-hours:72}")
    private int i4LookbackHours;

    /** 单轮上限。**积压很多时一次全跑会把退款通道打满**，超出的留待下一轮 */
    @Value("${shop.job.refund-retry.limit:200}")
    private int limit;

    public RefundRetryJob(AfterSaleService afterSaleService, JobSupport jobs) {
        this.afterSaleService = afterSaleService;
        this.jobs = jobs;
    }

    private BasicRefundExecutionService executions;

    @Autowired
    public void setRefundExecutions(BasicRefundExecutionService executions) {
        this.executions = executions;
    }

    @Scheduled(cron = "${shop.job.refund-retry.cron:0 */10 * * * *}")
    // 10 分钟一次。锁 9 分钟（小于间隔）：真卡死时下一轮能接手，不至于整条退款重试停摆
    @SchedulerLock(name = "refund-retry", lockAtLeastFor = "PT30S", lockAtMostFor = "PT9M")
    public void scan() {
        jobs.run("refund-retry", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "refund-retry";
    }

    @Bean
    public JobDeclaration refundRetryDeclaration() {
        return new JobDeclaration("refund-retry", "退款续跑",
                "把卡在「退款中」的售后单接着往下走：先回退分账，再退款；分账仍回退不了的留待下一轮。"
                        + "顺带报「已退款而分账没回退」的单 —— 那一类修不了，只能人工追",
                "shop-core", "*/30 * * * * *", true,
                480, 540, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        long now = System.currentTimeMillis();
        if (executions != null) {
            executions.recoverDue(now, limit);
        }

        /*
         * I4 先查再续跑，顺序是有意的：
         * 续跑会把单子推到 REFUNDED，如果它这一轮里回退分账仍然失败，
         * **那条新的违反要等下一轮才被看见**。先查一遍，报的是上一轮之前就存在的，
         * 与「这一轮刚制造出来的」分得开 —— 否则告警数字会跟着重试节奏抖。
         */
        List<String> unreversed = afterSaleService.refundedWithoutSplitReversal(
                now - i4LookbackHours * 3_600_000L, limit);
        if (!unreversed.isEmpty()) {
            /*
             * **这一条修不了，所以只报。** 钱已经退给买家、分账没收回来，
             * 差额是实打实的损失。自动补一次 reverseSplit 听起来对，其实危险：
             * 走到这一步说明第一次就失败过，而原因通常是分账已过期 ——
             * 再调只会再失败，而日志里那行「已重试」会让人以为有人在处理。
             */
            log.error("[refund-retry] **I4 违反 {} 条**：已退款而分账没回退，"
                            + "钱追不回来，需人工处置（不自动重试）。样本：{}",
                    unreversed.size(), unreversed.subList(0, Math.min(5, unreversed.size())));
        }

        long cutoff = now - stuckMinutes * 60_000L;
        List<String> stuck = afterSaleService.stuckRefundNos(cutoff, limit);
        if (stuck.isEmpty()) {
            /*
             * 没有卡住的单时 detail 仍要带上 I4 —— 它与续跑是两件事。
             * 早先这里直接 return null（JobSupport 用它区分「跑了但没事」），
             * 而那会让 I4 的违反数**在没有卡单的日子里整个消失**，
             * 恰好是最平静、最没人看日志的那些天。
             */
            return JobResult.ok(unreversed.isEmpty() ? null
                    : "无卡单；**I4 违反 %d 条**（已退款而分账未回退）".formatted(unreversed.size()));
        }

        int advanced = 0;
        int stillStuck = 0;
        for (String no : stuck) {
            try {
                afterSaleService.resumeRefund(no, SYSTEM);
                advanced++;
            } catch (RuntimeException e) {
                /*
                 * 分账回退失败（SPLIT_EXPIRED）是**预期内的结果**而不是故障：
                 * 单子留在 REFUNDING 等下一轮，与 doRefund 的顺序注释一致。
                 * 所以这里是 warn 不是 error —— 但**持续不为零要人去看**，
                 * 那意味着有一批单永远退不掉，而用户在等钱。
                 */
                stillStuck++;
                log.warn("[refund-retry] {} 仍未推进：{}", no, e.getMessage());
            }
        }

        if (stillStuck > 0) {
            log.warn("[refund-retry] 扫 {} 笔：推进 {} · **仍卡住 {}** —— "
                            + "持续不为零说明有单永远退不掉，要查分账回退链路",
                    stuck.size(), advanced, stillStuck);
        }
        /*
         * **扫到几笔要写进 detail**：只报「推进 0 笔」的话，
         * 「没有卡住的单」与「查询条件写错、一笔都没扫到」长得一模一样，
         * 而后者才是最该查的那种。
         */
        return JobResult.ok("扫 %d 笔（推进 %d · 仍卡住 %d）· I4 违反 %d"
                .formatted(stuck.size(), advanced, stillStuck, unreversed.size()));
    }
}
