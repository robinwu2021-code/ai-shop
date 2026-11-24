package ai.neargo.shop.approval.opskit;

import ai.neargo.opskit.connector.approval.ApprovalClient;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 每日对账（B5）：送审后超过 24 小时仍未结束的关联，逐条向 OpsKit 查一次，补上丢失的回调。
 * 业务时区 Asia/Taipei 凌晨 3 点；调度只在 worker profile 开启（SchedulingConfig）。
 */
public class OpsKitReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(OpsKitReconcileJob.class);
    static final int OLDER_THAN_HOURS = 24;
    static final int BATCH = 200;

    private final ApprovalClient client;
    private final ApprovalLinkStore links;
    private final ApprovalOutcomes outcomes;

    public OpsKitReconcileJob(ApprovalClient client, ApprovalLinkStore links, ApprovalOutcomes outcomes) {
        this.client = client;
        this.links = links;
        this.outcomes = outcomes;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Taipei")
    @SchedulerLock(name = "opskit-approval-reconcile", lockAtMostFor = "PT30M")
    public void reconcile() {
        int checked = 0;
        for (var row : links.stalePending(OLDER_THAN_HOURS, BATCH)) {
            try {
                outcomes.reconciled(row, client.reconcile(row.subjectType(), row.subjectRef(), row.processCode()));
                checked++;
            } catch (RuntimeException e) {
                log.error("[opskit] 对账失败 {} {}: {}", row.subjectType(), row.subjectRef(), e.getMessage());
            }
        }
        if (checked > 0) {
            log.info("[opskit] 对账完成 {} 条", checked);
        }
    }
}
