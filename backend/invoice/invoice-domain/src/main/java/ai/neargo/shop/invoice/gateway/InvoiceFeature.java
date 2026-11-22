package ai.neargo.shop.invoice.gateway;

/**
 * 供应商支持的发票业务特性（TDD-发票-供应商能力定义完善 D2）。只用于页面提示与选择供应商时的说明；
 * 能不能送出以 {@link InvoiceGateway#preflight} 为准（与真实上传同一套映射，不会两处不一致）。
 */
public enum InvoiceFeature {
    /** 零税率（台湾需零税率原因与通关方式） */
    ZERO_RATED,
    /** 一张票里应税、免税、零税率并存 */
    MIXED_TAX,
    /** 免税 */
    EXEMPT,
    /** 买方打统编 */
    B2B,
    /** 存载具 */
    CARRIER,
    /** 捐赠 */
    DONATION
}
