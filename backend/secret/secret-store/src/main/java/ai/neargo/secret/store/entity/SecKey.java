package ai.neargo.secret.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * sec_key（db/secret V1 · TDD-支付服务-W5密钥模块）：密钥：调用外部 API 与被外部调用的每一把密钥。支付命名空间下包括：我方调渠道、渠道平台公钥、第三方与内部系统调我方、刷卡机。材料在版本表
 *
 * <p>字段注释取自 DDL，逐列定义以表结构说明 §6.8 为准。
 */
@Getter
@Setter
@TableName("sec_key")
public class SecKey extends SecBaseEntity {

    /** PAY / LOGISTICS / INVENTORY / NOTIFY / PLATFORM */
    private String namespace;

    /** 名称（给人看） */
    private String name;

    /** PAY_MERCHANT 收款商户调渠道 / CHANNEL_PLATFORM 渠道平台公钥与证书 / PARTNER_APP 第三方接入方 / INTERNAL_APP 内部系统 / TERMINAL 刷卡机 / PROVIDER 其他域的外部服务商 */
    private String ownerType;

    /** 归属对象 id：pay_merchant.id / pay_app.id / pay_terminal.id … */
    private Long ownerId;

    /** 对端系统码：NEWEBPAY / LINEPAY / PAYBY / WECHAT / ALIPAY / KUAIDI100 … */
    private String counterparty;

    /** OUTBOUND 我方调外部时使用 / INBOUND 外部调我方时我方验证 / COUNTERPARTY_VERIFY 验对端响应与回调 */
    private String direction;

    /** SIGN 签名 / VERIFY 验签 / HMAC / ENCRYPT 加解密 / MTLS 双向证书 */
    private String keyUsage;

    /** HMAC_SHA256 / RSA_SHA256 / AES_256_CBC / AES_256_GCM / ECDSA_P256 … */
    private String algorithm;

    /** SYMMETRIC_SECRET / PRIVATE_KEY / PUBLIC_KEY / CERTIFICATE / COMPOSITE（蓝新 HashKey + HashIV） */
    private String materialType;

    /** SANDBOX / PRODUCTION：生产密钥永不进入沙箱 */
    private String env;

    /** DRAFT / ACTIVE / SUSPENDED / REVOKED / EXPIRED */
    private String status;

    /** 当前 ACTIVE 版本号；0 = 尚无 */
    private Integer currentVersion;

    /** 适用策略 sec_key_policy.id；空 = 按 namespace + owner_type + env 取默认 */
    private Long secKeyPolicyId;

    /** 当前版本到期时间（证书有效期或策略最长使用期，取早者） */
    private LocalDateTime expiresAt;

    /** 最近使用（异步刷新） */
    private LocalDateTime lastUsedAt;

    /** 累计使用次数（异步累加，近似值） */
    private Long useCount;
}
