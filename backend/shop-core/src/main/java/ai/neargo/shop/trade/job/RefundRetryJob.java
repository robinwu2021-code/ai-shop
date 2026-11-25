package ai.neargo.shop.trade.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.PointsRefundResourceService;
import org.springframework.beans.factory.annotation.Autowired;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private static final int MAX_OBSERVATIONS = 4096;
    private static final int I4_SAMPLE_SIZE = 5;

    // Process-local diagnostic deduplication, independent of the persisted work and its retries.
    private final LinkedHashMap<Observation, String> observations = new LinkedHashMap<>(16, 0.75f, true);

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
    private PointsRefundResourceService pointsResources;

    @Autowired
    public void setPointsResources(PointsRefundResourceService pointsResources) {
        this.pointsResources = pointsResources;
    }

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
        String runId = invocation == null ? UUID.randomUUID().toString() : invocation.runId();
        long now = System.currentTimeMillis();
        if (executions != null) {
            executions.recoverDue(now, limit);
        }
        // 库存有独立批次，不能被持续占满的资金/权益恢复队列饿死。
        List<String> stock = pointsResources == null ? List.of() : pointsResources.pendingNos(limit);
        int stockAdvanced = 0;
        int alerts = 0;
        for (String no : stock) {
            try {
                if (pointsResources.recover(no)) {
                    stockAdvanced++;
                    recovered(runId, "stock", no);
                } else {
                    alerts += pending(runId, "stock", no, "STOCK_PENDING");
                }
            } catch (RuntimeException failure) {
                alerts += pending(runId, "stock", no, reason(failure, "STOCK_RECOVERY_FAILED"));
            }
        }

        // I4 先查再续跑，只报人工处置，不触发分账重试。
        List<String> unreversed = afterSaleService.refundedWithoutSplitReversal(
                now - i4LookbackHours * 3_600_000L, limit);
        List<String> newI4 = new ArrayList<>();
        for (String no : unreversed) {
            if (observe("i4", no, "SPLIT_NOT_REVERSED")) {
                newI4.add(no);
            }
        }
        if (!newI4.isEmpty()) {
            alerts += newI4.size();
            log.error("refund.recovery.i4 runId={} reasonCode=SPLIT_NOT_REVERSED count={} newCases={} samples={} action=MANUAL_REVIEW",
                    runId, unreversed.size(), newI4.size(), newI4.subList(0, Math.min(I4_SAMPLE_SIZE, newI4.size())));
        }

        List<String> stuck = afterSaleService.stuckRefundNos(now - stuckMinutes * 60_000L, limit);
        Map<String, String> failures = new LinkedHashMap<>();
        for (String no : stuck) {
            try {
                afterSaleService.resumeRefund(no, SYSTEM);
            } catch (RuntimeException failure) {
                failures.put(no, reason(failure, "REFUND_RECOVERY_FAILED"));
            }
        }
        // 正常返回也可能仍在等待资金凭证。只统计已经落库的退款结果。
        Set<String> refunded = Set.of();
        String pendingReason = "REFUND_PENDING";
        if (!stuck.isEmpty()) {
            try {
                refunded = afterSaleService.refundedNosForRecovery(stuck);
            } catch (DataAccessException failure) {
                // This diagnostic read must not turn a completed recovery run into a scheduler retry.
                pendingReason = "REFUND_STATUS_UNCONFIRMED";
            }
        }
        for (String no : stuck) {
            if (refunded.contains(no)) {
                recovered(runId, "cash", no);
            } else {
                alerts += pending(runId, "cash", no, failures.getOrDefault(no, pendingReason));
            }
        }

        int refundPending = stuck.size() - refunded.size();
        int stockPending = stock.size() - stockAdvanced;
        log.info("refund.recovery.run runId={} refundScanned={} refundAdvanced={} refundPending={} "
                        + "stockScanned={} stockAdvanced={} stockPending={} i4Count={} alerts={}",
                runId, stuck.size(), refunded.size(), refundPending,
                stock.size(), stockAdvanced, stockPending, unreversed.size(), alerts);
        if (stuck.isEmpty() && stock.isEmpty() && unreversed.isEmpty()) {
            return JobResult.ok(null);
        }
        return JobResult.ok("runId=%s 退款扫 %d 笔（推进 %d · 待处理 %d）· 库存扫 %d 笔（推进 %d · 待处理 %d）· I4 违反 %d · 新告警 %d"
                .formatted(runId, stuck.size(), refunded.size(), refundPending,
                        stock.size(), stockAdvanced, stockPending, unreversed.size(), alerts));
    }

    private int pending(String runId, String stage, String no, String reason) {
        if (!observe(stage, no, reason)) {
            return 0;
        }
        log.warn("refund.recovery.{} runId={} afterSaleNo={} reasonCode={}", stage, runId, no, reason);
        return 1;
    }

    private synchronized boolean observe(String stage, String no, String reason) {
        String previous = observations.put(new Observation(stage, no), reason);
        if (observations.size() > MAX_OBSERVATIONS) {
            observations.pollFirstEntry();
        }
        return !reason.equals(previous);
    }

    private synchronized void recovered(String runId, String stage, String no) {
        if (observations.remove(new Observation(stage, no)) != null) {
            log.info("refund.recovery.{} runId={} afterSaleNo={} result=RECOVERED", stage, runId, no);
        }
    }

    private static String reason(RuntimeException failure, String fallback) {
        return failure instanceof BizException biz ? biz.errorCode().name() : fallback;
    }

    private record Observation(String stage, String afterSaleNo) { }
}
