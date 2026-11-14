package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts;
import ai.neargo.shop.commerce.dto.TaxSourceNoticeContracts;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface TaxSourceNoticeInternalApi {
    String RECEIVE_PATH = "/internal/commerce/tax-source-notices";

    @PostExchange(RECEIVE_PATH)
    SourceNoticeContracts.Receipt receive(@RequestBody TaxSourceNoticeContracts.Notice notice);
}
