package ai.neargo.shop.paybridge.approval;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款复核送审（TDD-基础能力库对齐 §2.2 1e）。退款单在支付事务里建成待复核，支付不跨事务送审（见 {@link PayApprovalSupport}），
 * 所以由本任务每 5 分钟把待复核、还没送过当前修订的退款送审；提交人 = 退款申请人，审批服务据此不让他自己批（Q12）。
 */
@Component
public class PayRefundReviewSubmitter {

    private static final Logger log = LoggerFactory.getLogger(PayRefundReviewSubmitter.class);

    private final PayApprovalSupport support;
    private final PayRefundReviewApprovalSubject subject;

    public PayRefundReviewSubmitter(PayApprovalSupport support, PayRefundReviewApprovalSubject subject) {
        this.support = support;
        this.subject = subject;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    @SchedulerLock(name = "pay-refund-review-approval-submit", lockAtMostFor = "PT4M")
    public void submitPending() {
        if (!support.enabled(PayRefundReviewApprovalSubject.SUBJECT_TYPE)) return;
        int sent = 0;
        for (var r : subject.pending()) {
            try {
                if (support.submit(PayRefundReviewApprovalSubject.SUBJECT_TYPE, r.payRefundNo(),
                        PayRefundReviewApprovalSubject.revisionOf(r), r.requestedBy(), r.requestedBy(), null)) sent++;
            } catch (RuntimeException e) {
                log.warn("approval.refund_review_submit_failed payRefundNo={} error={}", r.payRefundNo(),
                        e.getClass().getSimpleName());
            }
        }
        if (sent > 0) log.info("approval.refund_review_submitted count={}", sent);
    }
}
