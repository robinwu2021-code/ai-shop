package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.entity.StlSettleBatch;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 结算批次送审（方案-第一批审批工作流 T7）。
 * <ul>
 *   <li>放行：对账任务把批次挂起后，本任务每 5 分钟把「系统挂起、还没人处置过」的批次送审（提交人 = 系统）；
 *       人工挂起过的（审批未通过回写的也算）不自动再送，由运营点「放行」重新送审（Q4）</li>
 *   <li>放款：运营点「放款」即送审（{@link #payoutRequested}）</li>
 * </ul>
 */
@Component
public class SettleApprovalSubmitter {

    private static final Logger log = LoggerFactory.getLogger(SettleApprovalSubmitter.class);

    private final PayApprovalSupport support;
    private final SettleBatchService batches;

    public SettleApprovalSubmitter(PayApprovalSupport support, SettleBatchService batches) {
        this.support = support;
        this.batches = batches;
    }

    public boolean releaseEnabled() {
        return support.enabled(SettleReleaseApprovalSubject.SUBJECT_TYPE);
    }

    public boolean payoutEnabled() {
        return support.enabled(SettlePayoutApprovalSubject.SUBJECT_TYPE);
    }

    /** 运营点「放行」：送审当前修订。 */
    public void releaseRequested(String batchNo, String actor) {
        batches.revision(batchNo).ifPresent(r ->
                support.submit(SettleReleaseApprovalSubject.SUBJECT_TYPE, batchNo, r.revision(), actor, null, null));
    }

    /** 运营点「放款」：送审当前修订。 */
    public void payoutRequested(String batchNo, String actor) {
        batches.revision(batchNo).ifPresent(r ->
                support.submit(SettlePayoutApprovalSubject.SUBJECT_TYPE, batchNo, r.revision(), actor, null, null));
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    @SchedulerLock(name = "settle-release-approval-submit", lockAtMostFor = "PT4M")
    public void submitBlocked() {
        if (!releaseEnabled()) return;
        int sent = 0;
        var blocked = PayApprovalSupport.asApprovalService(() -> batches.opsBatches(StlSettleBatch.BLOCKED, null));
        for (var b : blocked) {
            if (b.decidedBy() != null) continue; // 人工处置过的不自动再送
            try {
                var r = batches.revision(b.batchNo()).orElse(null);
                if (r != null && support.submit(SettleReleaseApprovalSubject.SUBJECT_TYPE, b.batchNo(), r.revision(), "SYSTEM", "对账任务", null)) sent++;
            } catch (RuntimeException e) {
                log.warn("[approval] 批次 {} 送审失败：{}", b.batchNo(), e.toString());
            }
        }
        if (sent > 0) log.info("[approval] 挂起批次送审 {} 条", sent);
    }
}
