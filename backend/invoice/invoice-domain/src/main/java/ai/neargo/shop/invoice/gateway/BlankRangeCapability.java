package ai.neargo.shop.invoice.gateway;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.util.List;

/**
 * 空白未使用字轨上报（TDD-发票-加值中心模板-鲸跃与汎宇 §4）：期别结束后，把配到却没用掉的号码区间报给服务商转财政部。
 * 汎宇对应 E0402。一次上报同一期别、同一字轨、同一发票类别的若干区间。失败抛 {@link ProviderException}。
 */
public interface BlankRangeCapability {

    void reportBlank(ProviderAccount account, BlankReport report);

    /**
     * @param sellerTaxId 营业人统编（无总分支机构时总公司与分支机构都填它）
     * @param ranges      未用区间，号码 8 位、不含字轨
     */
    record BlankReport(String periodCode, String invoiceType, String track, String sellerTaxId,
            List<TrackCapability.TrackRange> ranges) {}
}
