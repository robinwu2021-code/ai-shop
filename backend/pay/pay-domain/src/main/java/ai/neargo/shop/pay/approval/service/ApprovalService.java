package ai.neargo.shop.pay.approval.service;

import ai.neargo.shop.pay.approval.entity.PayApproval;

/** 两人复核（通用：退款、出款、密钥启用、规则生效…）。申请人、第一复核人、第二复核人互不相同 */
public interface ApprovalService {

    /** 开一张复核单；同一对象已有进行中的（{@code open_key}）返回那张 */
    PayApproval open(String subjectType, Long subjectId, String subjectSha256, Long amountMinor, String currency,
                     String requestedBy);

    /**
     * 复核一步：PENDING_FIRST → PENDING_SECOND → APPROVED；任何一步驳回 → REJECTED。
     * 复核人与申请人或上一位相同 → {@code PAY_APPROVAL_SAME_PERSON}
     */
    PayApproval decide(Long approvalId, String reviewer, boolean approve, String reason);
}
