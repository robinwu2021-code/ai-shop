package ai.neargo.shop.pay.acquiring.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_exception（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：支付异常：关单后支付、金额不符、重复支付等需要处置的事实（修 R18：关单后成功不再静默改成功）
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_exception")
public class PayException extends PayBaseEntity {

    /** LATE_PAYMENT / AMOUNT_MISMATCH / DUPLICATE_PAYMENT / CURRENCY_MISMATCH */
    private String kind;

    /** 支付单 pay_order.id */
    private Long payOrderId;

    /** 通道尝试 stl_payment.id */
    private Long stlPaymentId;

    /** 涉及金额 */
    private Long amountMinor;

    /** 币种 */
    private String currency;

    /** OPEN / AUTO_REFUNDING / RESOLVED / IGNORED */
    private String status;

    /** 处置结论（只放结论） */
    private String resolution;

    /** 说明 */
    private String note;

    /** 处置人 */
    private String handledBy;

    /** 处置时间 */
    private LocalDateTime handledAt;
}
