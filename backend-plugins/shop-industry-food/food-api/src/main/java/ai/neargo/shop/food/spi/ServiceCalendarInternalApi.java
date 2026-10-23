package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCalendarReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Calendar command from the authenticated platform; no public caller may provide trusted identity coordinates. */
public interface ServiceCalendarInternalApi {
    String SAVE_PATH = "/internal/food/service-calendar/save";
    @PostExchange(SAVE_PATH)
    OperationVO saveCalendar(@RequestBody Command command);

    record Command(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
                   @NotNull LocalDate localDate, @NotBlank @Size(max=32) String actorNo,
                   @NotBlank @Size(max=128) String idempotencyKey, @NotNull @Valid SaveCalendarReq calendar) { }
}
