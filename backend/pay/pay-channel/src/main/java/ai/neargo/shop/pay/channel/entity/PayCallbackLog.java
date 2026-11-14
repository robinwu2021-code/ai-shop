package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * pay_callback_log（db/pay V4–V6 · TDD-支付服务-I2渠道统一封装 S1）：回调记录（③ 渠道 → 支付）：统一回调入口收到的每一条通知（含浏览器跳回）。先落库、再验签处理
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_callback_log")
public class PayCallbackLog extends PayBaseEntity {

    /** 追踪号：按单回查到原支付单的追踪号（解析不出时为空） */
    private String traceId;

    /** 路径里的渠道码 */
    private String payChannel;

    /** PAY / REFUND / ONBOARDING / PAYOUT / RETURN */
    private String notifyType;

    /** 路径里的引用原文（pay_merchant_no 或 onboarding_no，对外单号） */
    private String pathRef;

    /** PATH 子路径 / QUERY 参数 / LEGACY 旧地址别名 */
    private String routeStyle;

    /** 解析出的收款商户 pay_merchant.id */
    private Long payMerchantId;

    /** 解析出的进件单 pay_onboarding.id */
    private Long payOnboardingId;

    /** GET / POST */
    private String httpMethod;

    /** 来源 IP（经可信代理头） */
    private String remoteIp;

    /** 请求头脱敏副本 */
    private String headersMasked;

    /** 报文脱敏副本（验签前仅存指纹 + 前缀） */
    private String bodyMasked;

    /** 完整原文 pay_raw_payload.id（含头与体；密钥模块就绪前为空） */
    private Long payRawPayloadId;

    /** 原文哈希（重复通知识别） */
    private String rawSha256;

    /** PENDING / PASSED / FAILED / MERCHANT_MISMATCH / STALE */
    private String verifyResult;

    /** 通知里声明的金额（最小单位） */
    private Long notifyAmountMinor;

    /** 通知币种 */
    private String notifyCurrency;

    /** 通知里的手续费 */
    private Long feeMinor;

    /** 通知金额与支付单 / 退款单是否一致；不一致记 pay_exception 且不推进成功 */
    private Boolean amountMatched;

    /** 映射后的统一状态：SUCCEEDED / FAILED / CLOSED / REFUNDED … */
    private String unifiedStatus;

    /** RECEIVED / APPLIED / DUPLICATE / REJECTED / ERROR */
    private String outcome;

    /** 原因 */
    private String outcomeReason;

    /** 关联支付单 pay_order.id */
    private Long payOrderId;

    /** 关联退款单 pay_refund.id */
    private Long payRefundId;

    /** 关联通道尝试 stl_payment.id */
    private Long stlPaymentId;

    /** 报文里的我方单号（原样） */
    private String outTradeNo;

    /** 通道单号 */
    private String channelTradeNo;

    /** 回给渠道的应答 */
    private String ackBody;

    /** 重放自哪条回调 pay_callback_log.id */
    private Long replayOfId;

    /** 重放操作人 */
    private String replayedBy;

    /** 收到时间 */
    private LocalDateTime receivedAt;

    /** 处理完成时间 */
    private LocalDateTime handledAt;
}
