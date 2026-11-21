package ai.neargo.shop.invoice.service;

import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileVO;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.util.List;
import java.util.Optional;

/** 主体的税制开通与字轨号段登记。都是主体级配置，scope 里的门店只用于号段「本店专用」的归属判断。 */
public interface InvoiceSetupService {

    Optional<RegimeProfileVO> profile(Scope scope);

    RegimeProfileVO saveProfile(Scope scope, RegimeProfileReq request);

    List<NumberRangeVO> ranges(Scope scope, String periodCode);

    NumberRangeVO addRange(Scope scope, NumberRangeReq request);
}
