package ai.neargo.shop.pay.openapp.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_app（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：接入方：第三方系统或内部系统（内部系统也注册，用于来源可拆）
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_app")
public class PayApp extends PayBaseEntity {

    /** 对外 AppId（请求头 X-App-Id 传的就是它） */
    private String appNo;

    /** 名称 */
    private String name;

    /** PARTNER / INTERNAL */
    private String ownerType;

    /** ACTIVE / SUSPENDED / REVOKED */
    private String status;

    /** 逗号分隔：pay:create,pay:read,pay:refund,ledger:read,settle:read */
    private String scopes;

    /** IP / CIDR 白名单 */
    private String ipWhitelist;

    /** 结果推送地址 */
    private String webhookUrl;

    /** 允许的跳回域名 */
    private String returnUrlWhitelist;

    /** 限流 */
    private Integer rateLimitQps;

    /** 日退款上限；0 = 不允许退款 */
    private Long dailyRefundLimitMinor;

    /** 沙箱 app（路由到 TEST 渠道） */
    private Boolean sandbox;

    /** 签名方案：HMAC（AppSecret）/ RSA（接入方私钥 + 平台证书）；一个 app 只能一种（V14） */
    private String signType;
}
