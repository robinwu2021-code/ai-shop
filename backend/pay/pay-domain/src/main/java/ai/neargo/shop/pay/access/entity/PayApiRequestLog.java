package ai.neargo.shop.pay.access.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_api_request_log（db/pay V5 · 写入方 TDD-支付服务-I5接入层 S2）：业务方 → 支付的每一次请求（四向记录之①）。
 * 鉴权失败也写一行（{@code REJECTED_AUTH}）；请求体只存脱敏副本。
 */
@Getter
@Setter
@TableName("pay_api_request_log")
public class PayApiRequestLog extends PayBaseEntity {

    private String traceId;
    /** OPEN_API / TERMINAL_API / INTERNAL_PORT / INTERNAL_HTTP / BIZ_API / OPS_API / CHECKOUT */
    private String entry;
    private String api;
    private String payAction;
    /** PARTNER / INTERNAL / TERMINAL / BIZ / OPS / CHECKOUT */
    private String sourceType;
    private Long sourceId;
    private String appNo;
    private String clientType;
    private String clientIp;
    private String userAgentDigest;
    private String deviceId;
    private String operatorNo;
    private String bizType;
    private String bizNo;
    private String idempotencyKey;
    private Long storeId;
    private Long requestAmountMinor;
    private String requestCurrency;
    private Long payOrderId;
    private Long payRefundId;
    private String requestMasked;
    private Long requestRawId;
    private String responseMasked;
    private Integer httpStatus;
    private String resultCode;
    /** ACCEPTED / REJECTED_AUTH / REJECTED_PARAM / REJECTED_FORBIDDEN / DUPLICATE / ERROR */
    private String outcome;
    private Integer elapsedMs;
    private LocalDateTime receivedAt;
}
