package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Internal reads carry original date range and never caller-supplied opening rules. */
public interface StoreCalendarSourceInternalApi {
    String CAPTURE_PATH = "/internal/food/store-calendar-sources/capture";

    @PostExchange(CAPTURE_PATH)
    Facts capture(@RequestBody Query query);
}
