package ai.neargo.shop.pay.approval.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_approval（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：两人复核单（通用）：退款、出款、密钥启用、资金路径切换、收款商户启用、复核规则生效
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_approval")
public class PayApproval extends PayBaseEntity {

    /** REFUND / PAYOUT / KEY_ENABLE / FUNDS_PATH_SWITCH / MERCHANT_ACTIVATE / REVIEW_RULE_ACTIVATE */
    private String subjectType;

    /** 对象 id（按 subject_type：pay_refund / stl_payout / sec_key_version / pay_merchant / pay_refund_review_rule） */
    private Long subjectId;

    /** 提交复核时对象内容摘要；复核时对象被改动即失效 */
    private String subjectSha256;

    /** 涉及金额 */
    private Long amountMinor;

    /** 币种 */
    private String currency;

    /** 申请人 */
    private String requestedBy;

    /** PENDING_FIRST / PENDING_SECOND / APPROVED / REJECTED / CANCELLED / EXPIRED */
    private String status;

    /** 进行中时 = subject_type|subject_id：一个对象同时只有一张进行中复核单 */
    private String openKey;

    /** 第一复核人 */
    private String firstReviewer;

    /** 时间 */
    private LocalDateTime firstDecidedAt;

    /** 第二复核人 */
    private String secondReviewer;

    /** 时间 */
    private LocalDateTime secondDecidedAt;

    /** 驳回原因 */
    private String rejectReason;
}
