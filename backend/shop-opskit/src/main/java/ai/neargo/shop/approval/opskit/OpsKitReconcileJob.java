package ai.neargo.shop.approval.opskit;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.opskit.connector.Reconciler;
import ai.neargo.opskit.connector.approval.ApprovalClient;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日对账（B5 · ADR-067 A12/C2）：长时间未结束的关联逐条向 OpsKit 查一次。
 * <ul>
 *   <li>OpsKit 已有结论 → 补上丢失的回调（{@link ApprovalOutcomes#reconciled}）</li>
 *   <li>PENDING_SUBMIT（送审没投成，或投成了但没来得及回填）且 OpsKit 查不到 / 还在审 →
 *       走发件箱消费者同一条幂等路径补投（{@link OpsKitSubmitConsumer#deliver}）：查不到就新建，还在审就重放回填实例号</li>
 *   <li>PENDING（OpsKit 回过实例号）却查不到 → 不补投：同一幂等键重投也回填不了这一行，只告警交给人</li>
 * </ul>
 *
 * <p>生产跑 {@code api,ops}，{@code @EnableScheduling} 不生效；真正的触发是独立调度器按 {@link JobDeclaration}
 * 调 {@link #run}。{@code @Scheduled} 只是 worker profile 下的遗留触发器，与声明同一 cron。
 * 需要 OpsKit 对接与作业都开着（{@code shop.opskit.enabled} + {@code shop.job.enabled}）。
 */
@Component
@ConditionalOnProperty(name = {"shop.opskit.enabled", "shop.job.enabled"}, havingValue = "true")
public class OpsKitReconcileJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(OpsKitReconcileJob.class);
    /** 已送审、等人审批：审批本来就以天计，24 小时内没回调不算异常。 */
    static final int PENDING_OLDER_THAN_HOURS = 24;
    /**
     * 待投递：发件箱从第一次失败到转 FAILED 约 72 分钟（OutboxDispatcher.MAX_BACKOFF），过了 2 小时还是 PENDING_SUBMIT，
     * 说明发件箱已经放弃或事件丢了 —— 是投递丢失而不是审批慢，不必再陪 24 小时。仍在重试的也无妨：补投与消费者同一幂等键。
     */
    static final int SUBMIT_OLDER_THAN_HOURS = 2;
    static final int BATCH = 200;

    private final ApprovalClient client;
    private final ApprovalLinkStore links;
    private final ApprovalOutcomes outcomes;
    private final OpsKitSubmitConsumer submitter;
    private final JobSupport jobs;

    public OpsKitReconcileJob(ApprovalClient client, ApprovalLinkStore links, ApprovalOutcomes outcomes,
                              OpsKitSubmitConsumer submitter, JobSupport jobs) {
        this.client = client;
        this.links = links;
        this.outcomes = outcomes;
        this.submitter = submitter;
        this.jobs = jobs;
    }

    // 业务时区凌晨 3 点；独立调度器按系统时区解析同一 cron
    @Scheduled(cron = "${shop.job.opskit-approval-reconcile.cron:0 0 3 * * *}", zone = "Asia/Taipei")
    @SchedulerLock(name = "opskit-approval-reconcile", lockAtMostFor = "PT30M")
    public void reconcile() {
        jobs.run("opskit-approval-reconcile", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "opskit-approval-reconcile";
    }

    @Bean
    public JobDeclaration opskitApprovalReconcileDeclaration() {
        return JobDeclaration.daily("opskit-approval-reconcile", "OpsKit 审批对账",
                "长时间未结束的审批关联逐条向 OpsKit 查一次：补上丢失的结束回调；送审没投到 OpsKit 的按同一幂等键补投",
                "shop-opskit", "0 0 3 * * *");
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int checked = 0;
        int resubmitted = 0;
        int failed = 0;
        String lastError = null;
        for (var row : links.stalePending(SUBMIT_OLDER_THAN_HOURS, PENDING_OLDER_THAN_HOURS, BATCH)) {
            try {
                var o = client.reconcile(row.subjectType(), row.subjectRef(), row.processCode());
                outcomes.reconciled(row, o);
                if (ApprovalLinkStore.PENDING_SUBMIT.equals(row.status())
                        && (o.state() == Reconciler.State.NOT_FOUND || o.state() == Reconciler.State.IN_PROGRESS)) {
                    submitter.deliver(row.subjectType(), row.subjectRef(), row.revision());
                    log.warn("[opskit] 对账补投：{} {} revision {}（OpsKit {}）", row.subjectType(), row.subjectRef(), row.revision(), o.state());
                    resubmitted++;
                }
                checked++;
            } catch (RuntimeException e) {
                failed++;
                lastError = row.subjectType() + " " + row.subjectRef() + ": " + e.getMessage();
                log.error("[opskit] 对账失败 {} {}: {}", row.subjectType(), row.subjectRef(), e.getMessage());
            }
        }
        if (checked == 0 && failed == 0) {
            return JobResult.ok(null);
        }
        String detail = "对账 " + checked + " 条，补投 " + resubmitted + " 条" + (failed > 0 ? "，失败 " + failed + " 条" : "");
        log.info("[opskit] {}", detail);
        return failed > 0 ? JobResult.failed(detail, lastError) : JobResult.ok(detail);
    }
}
