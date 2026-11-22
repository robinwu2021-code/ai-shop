package ai.neargo.shop.invoice.gateway;

/**
 * 供应商能力的标准名（TDD-发票-供应商能力定义完善 D1）。领域、接口与页面只认它，不认厂商、不写 {@code instanceof}。
 * 由 {@link InvoiceGateways#capabilities} 从实现的能力接口与声明的上传报文统一推导，厂商不手写 —— 手写会和实现不一致。
 */
public enum ProviderCapability {
    /** 账号核验（{@link AccountCapability}） */
    VERIFY_ACCOUNT,
    /** 服务商配给号段（{@link RangeCapability}） */
    ALLOCATE_RANGE,
    /** 向服务商查单对账（{@link QueryCapability}） */
    QUERY_STATUS,
    /** 查配号方式、已下载字轨、上传下期字轨（{@link TrackCapability}） */
    MANAGE_TRACKS,
    /** 期末空白未使用字轨上报（{@link BlankRangeCapability}） */
    REPORT_BLANK,
    /** 注销：收 {@link ProviderOperation#REVOKE} 上传报文，且能查注销进度（{@link RevokeStatusCapability}） */
    REVOKE
}
