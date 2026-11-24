package ai.neargo.shop.invoice.gateway;

/**
 * 证明联 QR 加密金钥从哪来（TDD-发票-供应商能力定义完善 D5）。
 * 核验回传的金钥一律存；领号回传的只在声明为 {@link #RANGE} 时存（语义未确认前不当金钥用）。
 */
public enum QrKeySource {
    /** 商家在设置页手填 */
    MERCHANT,
    /** 账号核验时服务商回传（鲸跃 QueryOfficeInfo.QRCode） */
    VERIFY,
    /** 领号段时服务商回传 */
    RANGE
}
