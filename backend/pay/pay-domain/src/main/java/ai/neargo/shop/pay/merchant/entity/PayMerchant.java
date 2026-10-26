package ai.neargo.shop.pay.merchant.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_merchant（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1）：收款商户 = 渠道中的商户。一个主体（经其门店）对多个收款商户；每个只属于一个渠道、一个市场、一种接入模式、一种资金路径。取代 mch_payment_merchant
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_merchant")
public class PayMerchant extends PayBaseEntity {

    /** 收款商户号（对外：回调地址、结算单）。迁移时沿用 mch_payment_merchant.pay_merchant_no 原值 */
    private String payMerchantNo;

    /** STORE 商家门店开的 / PLATFORM 平台自己的（服务商号、平台账户、CN 平台直连号） */
    private String ownerType;

    /** 开户门店 mch_store.id；owner_type=PLATFORM 时为 NULL。其他门店共用经 pay_merchant_binding */
    private Long storeId;

    /** 渠道码 */
    private String payChannel;

    /** 市场码 */
    private String market;

    /** 收款与结算币种 */
    private String currency;

    /** MERCHANT_OWN / SERVICE_PROVIDER / PLATFORM_SUB */
    private String accessMode;

    /** DIRECT 结算到商户自己的账户（含微信二级商户）/ AGGREGATED 结算到平台账户 */
    private String fundsPath;

    /** 渠道商户号：蓝新 MerchantID / LINE Pay ChannelId / PayBy Partner-Id / 微信 mchid（SP 模式为服务商号） */
    private String channelMerchantId;

    /** 子商户号：微信 sub_mchid / 支付宝 smid / PayBy 子商户标识；无则空串（参与唯一键） */
    private String subMerchantId;

    /** SERVICE_PROVIDER / PLATFORM_SUB 时引用平台级收款商户 pay_merchant.id（取其密钥） */
    private Long parentPayMerchantId;

    /** 产出它的进件单 pay_onboarding.id；存量迁移行可空 */
    private Long payOnboardingId;

    /** 调用渠道用的密钥 sec_key.id（MERCHANT_OWN 必填；SP / 平台子商户取父商户的） */
    private Long secKeyId;

    /** 当前密钥版本号；交易冻结此值 */
    private Integer secKeyVersion;

    /** ONBOARDING / ACTIVE / REFUND_ONLY / SUSPENDED / CLOSED */
    private String status;

    /** 通道档位 */
    private String legalForm;

    /** 能否开票（下单前必须披露） */
    private Boolean invoiceCapable;

    /** PERSONAL_BANK_CARD / CORPORATE_BANK / MERCHANT_ID / IBAN */
    private String settleAccountType;

    /** 结算账户掩码；明文只在渠道 */
    private String settleAccountMasked;

    /** 渠道结算周期：T1 / T2 / WEEKLY … */
    private String settleCycle;

    /** 通道费承担方：MERCHANT / PLATFORM */
    private String feeBearer;

    /** 分账可回退授权；NULL = 未知按未授权 */
    private Boolean splitReversible;

    /** 同一支付方式多条可用路由时的选择顺序，小者优先 */
    private Integer priority;

    /** 开户时主体名快照（PLATFORM 为空） */
    private String entityNameSnapshot;

    /** 启用时间 */
    private LocalDateTime activatedAt;

    /** 启用人（审计字段） */
    private String activatedBy;

    /** 转 REFUND_ONLY 时间 */
    private LocalDateTime refundOnlyAt;

    /** 关闭时间 */
    private LocalDateTime closedAt;
}
