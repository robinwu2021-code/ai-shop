package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * pay_channel_call_log（db/pay V4–V6 · TDD-支付服务-I2渠道统一封装 S1）：渠道调用记录（② 支付 → 渠道）：每一次对渠道的 HTTP 调用，重试每次一行。由管线的 RecordingInterceptor 与 RecordingChannelClient 写，处理器无法绕过
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_channel_call_log")
public class PayChannelCallLog extends PayBaseEntity {

    /** 追踪号（来自请求记录，补查任务沿用原追踪号） */
    private String traceId;

    /** 单次调用号：同一次调用的多次重试共用 */
    private String invocationNo;

    /** 市场 */
    private String market;

    /** 渠道码 */
    private String payChannel;

    /** 接入模式 */
    private String accessMode;

    /** 场景：ONLINE_REDIRECT / ONLINE_SDK / ONLINE_QR / IN_STORE_CARD / IN_STORE_SCAN_CUSTOMER / IN_STORE_SHOW_QR / ANY */
    private String scene;

    /** 操作：PAY / CONFIRM / QUERY / CLOSE / VOID / REFUND / REFUND_QUERY / ONBOARDING_SUBMIT / ONBOARDING_QUERY / MEDIA_UPLOAD / STATEMENT_FETCH / PAYOUT / KEY_VERIFY */
    private String operation;

    /** 渠道接口名，如 MPG_GATEWAY / QUERY_TRADE / CREDIT_CLOSE / V3_REQUEST / V3_CONFIRM / ACQUIRE2_PLACE_ORDER */
    private String api;

    /** 以哪个收款商户的身份调 pay_merchant.id */
    private Long payMerchantId;

    /** PAY_ORDER / PAY_REFUND / ONBOARDING / STATEMENT / PAYOUT / KEY */
    private String refType;

    /** 业务对象 id（按 ref_type） */
    private Long refId;

    /** 支付单 pay_order.id */
    private Long payOrderId;

    /** 通道尝试 stl_payment.id */
    private Long stlPaymentId;

    /** 发给渠道的单号（字符串原样，便于按用户报障单号检索） */
    private String outTradeNo;

    /** 本次调用的第几次发送 */
    private Integer attempt;

    /** 重试自哪一行 pay_channel_call_log.id */
    private Long retryOfId;

    /** 来源：PARTNER / INTERNAL / TERMINAL / BIZ / OPS / CHECKOUT */
    private String sourceType;

    /** 来源 id：pay_app.id / pay_terminal.id / 账号 id（按 source_type） */
    private Long sourceId;

    /** 来源端：H5 / APP_IOS / APP_ANDROID / MINI_PROGRAM / POS / SERVER / OPS_WEB / BIZ_APP */
    private String clientType;

    /** 请求金额（最小单位） */
    private Long requestAmountMinor;

    /** 请求币种 */
    private String requestCurrency;

    /** 按渠道精度换算后实际发给渠道的金额字符串，如 TWD "1200"、AED "35.50" */
    private String channelAmountText;

    /** 换算所用精度（pay_channel_product_market.amount_scale） */
    private Integer amountScale;

    /** 渠道响应声明的金额（最小单位） */
    private Long responseAmountMinor;

    /** 渠道响应币种 */
    private String responseCurrency;

    /** 渠道手续费（以回执为准） */
    private Long feeMinor;

    /** GET / POST */
    private String httpMethod;

    /** 路径（不含主机与查询串里的密钥） */
    private String urlPath;

    /** 请求脱敏副本（递归脱敏） */
    private String requestMasked;

    /** 请求加密原文 pay_raw_payload.id */
    private Long requestRawId;

    /** 响应脱敏副本 */
    private String responseMasked;

    /** 响应加密原文 pay_raw_payload.id */
    private Long responseRawId;

    /** HTTP 状态码；超时为空 */
    private Integer httpStatus;

    /** 耗时 */
    private Integer elapsedMs;

    /** 响应里的通道单号 */
    private String channelTradeNo;

    /** 传输结果：SENT / RESPONDED / TIMEOUT / CONNECT_FAILED / REJECTED_BEFORE_SEND */
    private String outcome;

    /** 统一结果：SUCCEEDED / PENDING / FAILED / UNKNOWN（超时、5xx、未登记渠道码一律 UNKNOWN） */
    private String outcomeClass;

    /** 统一错误码 PayErrorCode */
    private String unifiedErrorCode;

    /** 渠道原始错误码 */
    private String errorCode;

    /** 渠道原始错误信息 */
    private String errorMsg;

    /** 本次生效的容错策略摘要（超时 / 重试次数 / 是否可重发） */
    private String faultPolicy;

    /** 发送时熔断器状态：CLOSED / HALF_OPEN / OPEN */
    private String breakerState;

    /** 发出时间 */
    private LocalDateTime sentAt;
}
