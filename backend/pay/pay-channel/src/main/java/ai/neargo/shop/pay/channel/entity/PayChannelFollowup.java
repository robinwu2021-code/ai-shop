package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * pay_channel_followup（db/pay V4–V6 · TDD-支付服务-I2渠道统一封装 S1）：「未知」补查任务：渠道调用结果不明（超时、5xx、未登记渠道码）时登记，按退避自动查询，直到有结论或转人工
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_channel_followup")
public class PayChannelFollowup extends PayBaseEntity {

    /** 原调用号（pay_channel_call_log.invocation_no） */
    private String invocationNo;

    /** 原追踪号：补查产生的调用记录沿用它 */
    private String traceId;

    /** 原操作：PAY / CONFIRM / REFUND / CLOSE / VOID / PAYOUT */
    private String operation;

    /** 渠道码 */
    private String payChannel;

    /** 收款商户 pay_merchant.id */
    private Long payMerchantId;

    /** 支付单 pay_order.id */
    private Long payOrderId;

    /** 通道尝试 stl_payment.id */
    private Long stlPaymentId;

    /** 退款单 pay_refund.id */
    private Long payRefundId;

    /** PENDING / RESOLVED / ESCALATED / CANCELLED */
    private String status;

    /** PENDING 时 = operation|stl_payment_id|pay_refund_id：同一对象同一操作只有一个进行中补查 */
    private String openKey;

    /** 已补查次数 */
    private Integer attempts;

    /** 下次补查时间（指数退避） */
    private LocalDateTime nextAt;

    /** 最近一次补查结果摘要 */
    private String lastResult;

    /** 得出结论的时间 */
    private LocalDateTime resolvedAt;

    /** 超过上限转人工时建的 pay_exception.id */
    private Long payExceptionId;
}
