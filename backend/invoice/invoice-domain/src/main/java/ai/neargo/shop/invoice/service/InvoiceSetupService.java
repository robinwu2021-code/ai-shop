package ai.neargo.shop.invoice.service;

import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderOptionsVO;
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

    /**
     * 号段水位巡检（全部已开通主体）：本期余量低于提醒值 / 用尽时提醒，期末前 {@code prefetchBefore} 内下期还没号段时提醒；
     * 开了自动领号且通道由服务商配号的，先自动领号再判断。同一事实只报一次。
     * @return [提醒条数, 自动领号次数]
     */
    int[] watchRanges(Instant now, Duration prefetchBefore);
}
