package ai.neargo.shop.pay.acquiring.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_refund_review_rule（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：退款复核规则（一行一个版本）：命中任一门槛 → 平台两人复核。某市场无 ACTIVE 规则 = 该市场全部复核
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_refund_review_rule")
public class PayRefundReviewRule extends PayBaseEntity {

    /** 规则码：同一规则的各版本共用 */
    private String ruleCode;

    /** 版本 */
    private Integer versionIndex;

    /** DRAFT / ACTIVE / RETIRED */
    private String status;

    /** ACTIVE 时 = rule_code：一条规则同一时刻一个生效版本 */
    private String activeKey;

    /** 市场（必填） */
    private String market;

    /** 币种（必填） */
    private String currency;

    /** 门店 mch_store.id；NULL = 全市场（门店级更具体者优先） */
    private Long storeId;

    /** 空串 = 全来源 */
    private String sourceType;

    /** 空串 = 全方式 */
    private String payMethod;

    /** 单笔 ≥ 此值 */
    private Long singleAmountMinor;

    /** 门店当日（市场时区）累计 + 本笔 ≥ 此值 */
    private Long dailyAmountMinor;

    /** 本笔占原单比例 ≥ 此值（万分比） */
    private Integer refundRatioBp;

    /** 原单支付超过 N 天 */
    private Integer afterDays;

    /** 强制全部复核 */
    private Boolean alwaysReview;

    /** 超时提醒（不自动通过） */
    private Integer reviewTimeoutMinutes;

    /** 起草人（审计字段） */
    private String draftedBy;

    /** 生效确认人，必须 ≠ 起草人 */
    private String activatedBy;

    /** 生效时间 */
    private LocalDateTime activatedAt;

    /** 退役时间 */
    private LocalDateTime retiredAt;
}
