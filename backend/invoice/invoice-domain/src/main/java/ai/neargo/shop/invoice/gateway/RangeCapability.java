package ai.neargo.shop.invoice.gateway;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;

/**
 * 服务商配给号段（{@link ProviderProfile.NumberingMode#PROVIDER_RANGE}）。取回入库后的取号与开立流程与手工登记的号段完全一样。
 * Turnkey 下号段由商家自己向国税局领、在 B 端登记，不实现本接口。
 */
public interface RangeCapability {

    /** @param periodCode 期别，如 {@code 11512}（民国 115 年 11-12 月） */
    AllocatedRange allocate(ProviderAccount account, String periodCode);

    record AllocatedRange(String applyId, String track, int startNo, int endNo, String invoiceType) {}
}
