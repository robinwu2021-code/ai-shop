package ai.neargo.shop.food.spi;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Read-only reservation contract; no invented resource authority commands. */
public interface ReservationInternalApi {
    String LIST_PATH="/internal/food/reservations/list";
    @PostExchange(LIST_PATH) PageData<ReservationVO> list(@RequestBody Lookup request);
    record Lookup(@NotBlank @Size(max=32) String entityNo,@NotBlank @Size(max=32) String storeNo,
            @Valid @NotNull ListReservationsQuery query) { }
}
