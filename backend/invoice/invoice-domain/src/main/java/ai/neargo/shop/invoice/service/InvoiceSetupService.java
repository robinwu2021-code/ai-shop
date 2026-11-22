package ai.neargo.shop.invoice.service;

import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderOptionsVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderTrackUploadReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderTrackUploadVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderTracksVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderVerificationVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileVO;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 主体的税制开通与字轨号段登记。都是主体级配置，scope 里的门店只用于号段「本店专用」的归属判断。 */
public interface InvoiceSetupService {

    Optional<RegimeProfileVO> profile(Scope scope);

    RegimeProfileVO saveProfile(Scope scope, RegimeProfileReq request);

    List<NumberRangeVO> ranges(Scope scope, String periodCode);

    NumberRangeVO addRange(Scope scope, NumberRangeReq request);

    /** 用已存的加值中心账号查服务商侧营业人资料，统编须与本主体一致；服务商回了 QR 金钥就顺手存起来。 */
    /** 某市场商家可选的开票供应商；{@code regimeCode} 为空取系统默认市场。 */
    ProviderOptionsVO providerOptions(String regimeCode);

    ProviderVerificationVO verifyProvider(Scope scope);

    /** 向加值中心申请并取回一段号码入库（{@code allocation_owner=PROVIDER}）；同一段重复取回不重复入库。 */
    NumberRangeVO fetchProviderRange(Scope scope, String periodCode);

    /** 服务商侧某期的配号方式与已下载字轨（{@code periodCode} 为空取当期）；供应商须有字轨能力。 */
    ProviderTracksVO providerTracks(Scope scope, String periodCode);

    /** 向服务商上传下一期字轨；只接受下一期、合计不超过 9999 本、每段 50 号的整倍数。 */
    ProviderTrackUploadVO uploadProviderTracks(Scope scope, ProviderTrackUploadReq request);

    /**
     * 期末空白字轨上报（全部已开通且供应商有空白字轨能力的主体）：上一期仍有未用号码的号段，
     * 按字轨 + 发票类别合并后上报；成功记 {@code blank_reported_at}，失败记原因下轮重试。
     * @return 成功上报的批数
     */
    int reportBlankRanges(Instant now);

    /**
     * 号段水位巡检（全部已开通主体）：本期余量低于提醒值 / 用尽时提醒，期末前 {@code prefetchBefore} 内下期还没号段时提醒；
     * 开了自动领号且通道由服务商配号的，先自动领号再判断。同一事实只报一次。
     * @return [提醒条数, 自动领号次数]
     */
    int[] watchRanges(Instant now, Duration prefetchBefore);
}
