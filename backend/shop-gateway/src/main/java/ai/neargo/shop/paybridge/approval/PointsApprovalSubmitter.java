package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.pay.points.admin.PointsAdjustmentService;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 积分申请送审（方案-第一批审批工作流 T8）。运营端提交成功（支付事务已提交）后立即送审；
 * 补送任务每 5 分钟找一次「待审却没有本修订审批单」的申请再送 —— 覆盖送审失败，以及补充计划定时任务自动生成的申请。
 */
@Component
public class PointsApprovalSubmitter {

    private static final Logger log = LoggerFactory.getLogger(PointsApprovalSubmitter.class);
    static final int RESEND_BATCH = 200;

    private final PayApprovalSupport support;
    private final PointsTreasuryService treasury;
    private final PointsAdjustmentService adjustments;

    public PointsApprovalSubmitter(PayApprovalSupport support, PointsTreasuryService treasury, PointsAdjustmentService adjustments) {
        this.support = support;
        this.treasury = treasury;
        this.adjustments = adjustments;
    }

    /** 资金池申请提交后调用；未启用审批时什么都不做。 */
    /** 资金池申请已切到审批：运营端原「审核」不能再直接决定。 */
    public boolean poolEnabled() {
        return support.enabled(PointsPoolRequestApprovalSubject.SUBJECT_TYPE);
    }

    /** 用户调账已切到审批：运营端原「审核」不能再直接决定。 */
    public boolean adjustmentEnabled() {
        return support.enabled(PointsAdjustmentApprovalSubject.SUBJECT_TYPE);
    }

    public void poolRequestSubmitted(String requestNo, String actor) {
        if (!support.enabled(PointsPoolRequestApprovalSubject.SUBJECT_TYPE)) return;
        treasury.request(requestNo).filter(r -> "SUBMITTED".equals(r.state())).ifPresent(r ->
                support.submit(PointsPoolRequestApprovalSubject.SUBJECT_TYPE, requestNo, r.version(), actor, null, null));
    }

    /** 调账单提交后调用。 */
    public void adjustmentSubmitted(String adjustmentNo, String actor) {
        if (!support.enabled(PointsAdjustmentApprovalSubject.SUBJECT_TYPE)) return;
        adjustments.adjustment(adjustmentNo).filter(v -> "SUBMITTED".equals(v.state())).ifPresent(v ->
                support.submit(PointsAdjustmentApprovalSubject.SUBJECT_TYPE, adjustmentNo, v.version(), actor, null, null));
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @SchedulerLock(name = "points-approval-resend", lockAtMostFor = "PT4M")
    public void resend() {
        int sent = 0;
        if (support.enabled(PointsPoolRequestApprovalSubject.SUBJECT_TYPE)) {
            for (var r : treasury.submitted(RESEND_BATCH)) {
                try {
                    if (support.submit(PointsPoolRequestApprovalSubject.SUBJECT_TYPE, r.requestNo(), r.version(), r.submittedBy(), null, null)) sent++;
                } catch (RuntimeException e) {
                    log.warn("[approval] 资金池申请 {} 补送失败：{}", r.requestNo(), e.toString());
                }
            }
        }
        if (support.enabled(PointsAdjustmentApprovalSubject.SUBJECT_TYPE)) {
            for (var v : adjustments.submitted(RESEND_BATCH)) {
                try {
                    if (support.submit(PointsAdjustmentApprovalSubject.SUBJECT_TYPE, v.adjustmentNo(), v.version(), v.submittedBy(), null, null)) sent++;
                } catch (RuntimeException e) {
                    log.warn("[approval] 调账单 {} 补送失败：{}", v.adjustmentNo(), e.toString());
                }
            }
        }
        if (sent > 0) log.info("[approval] 积分申请补送审批 {} 条", sent);
    }
}
