package ai.neargo.shop.pay.channel.api;

/** 接入模式：用谁的凭据调渠道（渠道接入统一封装设计 §2.1） */
public enum AccessMode {
    /** 商户自有凭据：钱直接进商户（微信直连、蓝新、LINE Pay） */
    MERCHANT_OWN,
    /** 服务商模式：平台凭据 + 子商户号（微信收付通、支付宝直付通） */
    SERVICE_PROVIDER,
    /** 平台子商户：平台账户下的子账户（PayBy 代收、测试渠道） */
    PLATFORM_SUB
}
