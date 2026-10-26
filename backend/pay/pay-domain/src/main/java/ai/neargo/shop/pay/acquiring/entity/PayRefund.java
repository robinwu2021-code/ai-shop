package ai.neargo.shop.pay.acquiring.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_refund（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：退款单：商家侧发起的一次退款申请，含复核。执行由 RefundExecutionService 落 stl_payment（direction=REFUND）
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_refund")
public class PayRefund extends PayBaseEntity {

    /** 退款单号（对外） */
    private String payRefundNo;

    /** 原支付单 pay_order.id（路由一律取原单快照；本表无路由列） */
    private Long payOrderId;

    /** 发起方 pay_app.id */
    private Long payAppId;

    /** 发起方退款单号（幂等） */
    private String outRefundNo;

    /** 业务售后单号（字符串，取代合成的 AS-{subOrderNo}，R21） */
    private String bizRefundNo;

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** 币种（= 原单） */
    private String currency;

    /** 退款金额 */
    private Long amountMinor;

    /** ONLINE 原路 / OFFLINE 线下（商家自退并上传凭证） */
    private String refundMode;

    /** 退款原因 */
    private String reason;

    /** REQUESTED / PENDING_REVIEW / PENDING_SECOND_REVIEW / APPROVED / DISPATCHED / SUCCEEDED / FAILED / REJECTED / OFFLINE_PENDING_PROOF */
    private String status;

    /** 发起人（审计字段） */
    private String requestedBy;

    /** 店长授权人（终端发起时，审计字段） */
    private String authorizedBy;

    /** 命中的复核规则版本 pay_refund_review_rule.id（一行即一个版本） */
    private Long payRefundReviewRuleId;

    /** 命中原因：SINGLE_AMOUNT / DAILY_AMOUNT / RATIO / AFTER_DAYS / ALWAYS / NO_ACTIVE_RULE */
    private String hitReasons;

    /** 复核单 pay_approval.id */
    private Long payApprovalId;

    /** 执行时生成的通道交易 stl_payment.id */
    private Long stlPaymentId;

    /** 线下退款凭证文件引用 */
    private String offlineProofRef;

    /** BIZ / TERMINAL / PARTNER */
    private String sourceType;

    /** 来源 id（同 pay_order.source_id） */
    private Long sourceId;

    /** 失败原因（原样告知商家） */
    private String failedReason;

    /** 成功时间 */
    private LocalDateTime succeededAt;
}
