package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.BusinessShareContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.ConfirmReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Staff-scoped business plans use a separate protocol from core monetary settlements. */
public interface BusinessShareInternalApi {
    String CREATE="/internal/food/business-shares/create";
    String GET="/internal/food/business-shares/get";
    String CONFIRM="/internal/food/business-shares/confirm";
    String CANCEL="/internal/food/business-shares/cancel";
    @PostExchange(CREATE) PlanVO create(@RequestBody CreateCommand command);
    @PostExchange(GET) PlanVO get(@RequestBody Lookup query);
    @PostExchange(CONFIRM) PlanVO confirm(@RequestBody ConfirmCommand command);
    @PostExchange(CANCEL) PlanVO cancel(@RequestBody CancelCommand command);
    record CreateCommand(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String checkNo,@Valid @NotNull CreateReq definition,@NotBlank String idempotencyKey) { }
    record Lookup(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String checkNo,
            @NotBlank String planNo) { }
    record ConfirmCommand(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String checkNo,@NotBlank String planNo,@Valid @NotNull ConfirmReq definition,
            @NotBlank String idempotencyKey) { }
    record CancelCommand(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String checkNo,@NotBlank String planNo,@Valid @NotNull CancelReq definition,
            @NotBlank String idempotencyKey) { }
}
