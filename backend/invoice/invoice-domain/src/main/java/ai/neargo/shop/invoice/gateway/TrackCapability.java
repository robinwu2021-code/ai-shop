package ai.neargo.shop.invoice.gateway;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.util.List;

/**
 * 字轨管理（TDD-发票-加值中心模板-鲸跃与汎宇 §4）：查服务商侧的配号方式与已下载字轨、上传下一期字轨。
 * 汎宇对应 Z33 / Z31 / Z32；鲸跃、Turnkey 没有，不实现。失败抛 {@link ProviderException}。
 */
public interface TrackCapability {

    /** 某期的配号方式；服务商没有该期记录时为 {@link AllocationMode#NONE}。 */
    AllocationMode allocationMode(ProviderAccount account, String periodCode);

    /** 某期已下载到服务商的字轨区间；没有时为空。 */
    List<TrackRange> downloadedTracks(ProviderAccount account, String periodCode);

    /** 上传下一期要用的字轨区间（服务商收下后排程配号）。区间合规由领域先校验。 */
    void uploadNextTracks(ProviderAccount account, TrackUpload upload);

    enum AllocationMode { NONE, AUTO, MANUAL }

    /** @param startNo / endNo 8 位号码（不含字轨） */
    record TrackRange(String invoiceType, String track, int startNo, int endNo) {}

    record TrackUpload(String periodCode, String invoiceType, String track, String sellerTaxId,
            List<TrackRange> ranges) {}
}
