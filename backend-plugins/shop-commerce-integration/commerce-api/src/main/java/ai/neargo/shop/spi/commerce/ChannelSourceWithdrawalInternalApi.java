package ai.neargo.shop.spi.commerce;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Original signed FOOD source endpoints return explicit native disposition, never a
 * missing-resource removal.
 */
public interface ChannelSourceWithdrawalInternalApi {
    String FOOD_CAPTURE_PATH = "/internal/food/commerce/sources/withdrawal";
    String FOOD_DISPOSITION_PATH = "/internal/food/commerce/sources/disposition";

    @PostExchange(FOOD_CAPTURE_PATH)
    ChannelSourceWithdrawalProvider.Facts capture(
            @RequestBody ChannelSourceWithdrawalProvider.Query query);

    @PostExchange(FOOD_DISPOSITION_PATH)
    ChannelSourceWithdrawalProvider.Disposition disposition(
            @RequestBody ChannelSourceWithdrawalProvider.Query query);
}
