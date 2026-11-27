package ai.neargo.shop.spi.approval;

import java.time.Instant;
import java.util.Optional;

/**
 * 轻量审批（TDD-基础能力库对齐 §2.2 1e，Q13、Q14）：店内、商户侧的快速审批 —— 改价、储值活动、退菜、现金收支这类，
 * 审批人是店主或商户管理层，当场批。存基础能力库 {@code sys_approval}。
 *
 * <p>与 {@link ApprovalPort} 分开：那个是运营侧的重审批，送审后由独立的审批与工单服务异步回调；审批人、节奏、存储都不同（Q14）。
 *
 * <p>业务表不再存审批状态、批准人：业务对象只留申请本身，审批事实只在这里一份。业务与本表可能不同库，
 * 所以业务读审批结果用 {@link #latest}，不要把结果抄回业务表。
 */
public interface LightApprovalPort {

    /**
     * 送审。同一对象同一修订重复送审返回原审批单（幂等）。{@link Submit#autoApproved()} 为真时直接批准
     * （申请人本身有权批，如店主自己改价），批准人即申请人，批准方式 {@link #MODE_AUTO}。
     */
    Approval submit(Submit submit);

    /**
     * 批或驳回待批的审批单。同一人重复同一结果返回原审批单；审批单已不是待批、过期、或审批人就是申请人 → {@code CONFLICT}。
     * 审批人有没有这个权限由调用方先判（权限是业务规则），这里不判。
     */
    Approval decide(String approvalNo, Decision decision);

    /** 该业务对象最近一次送审的审批单（修订号最大的那次） */
    Optional<Approval> latest(String tenantNo, String subjectType, String subjectRef);

    String MODE_AUTO = "AUTO";
    String MODE_MANUAL = "MANUAL";
    String PENDING = "PENDING";
    String APPROVED = "APPROVED";
    String REJECTED = "REJECTED";
    String VIA_APP = "APP";
    String VIA_TERMINAL = "TERMINAL";

    /**
     * @param processCode 审批流程编码，如 {@code PRICE_ADJUST}
     * @param revision    送审时业务对象的版本；批准时据此判断对象是否已变化
     * @param expiresAt   批准结果的有效期限，过期未批即不能再批；可空
     */
    record Submit(String tenantNo, String entityNo, String storeNo, String processCode, String subjectType,
                  String subjectRef, long revision, Long amountMinor, String currency, String reasonCode,
                  String reason, String submittedBy, boolean autoApproved, String via, Instant expiresAt) {
    }

    record Decision(String approverNo, boolean approve, String comment, String via) {
    }

    record Approval(String approvalNo, String subjectType, String subjectRef, long revision, String status,
                    String approvalMode, String submittedBy, String approverNo, Instant decidedAt, Instant expiresAt,
                    long version) {
        public boolean approved() {
            return APPROVED.equals(status);
        }
    }
}
