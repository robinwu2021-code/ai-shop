package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Notice;
import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Receipt;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The authenticated producer sends a durable hint to the original CIM owner, not an instruction to
 * publish event data.
 */
public interface SourceNoticeInternalApi {
    String RECEIVE_PATH = "/internal/commerce/source-notices";

    @PostExchange(RECEIVE_PATH)
    Receipt receive(@RequestBody Notice notice);
}
