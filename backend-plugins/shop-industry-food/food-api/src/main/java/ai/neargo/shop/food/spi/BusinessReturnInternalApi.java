package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.BusinessReturnContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Explicit local return protocol has no financial after-sale operation. */
public interface BusinessReturnInternalApi {
    String CREATE="/internal/food/business-returns/create";
    String GET="/internal/food/business-returns/get";
    String CANCEL="/internal/food/business-returns/cancel";
    @PostExchange(CREATE) ReturnVO create(@RequestBody CreateCommand command);
    @PostExchange(GET) ReturnVO get(@RequestBody Lookup query);
    @PostExchange(CANCEL) ReturnVO cancel(@RequestBody CancelCommand command);
    record CreateCommand(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String batchNo,@Valid @NotNull CreateReq definition,@NotBlank String idempotencyKey) { }
    record Lookup(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String returnNo) { }
    record CancelCommand(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String returnNo,@Valid @NotNull CancelReq definition,@NotBlank String idempotencyKey) { }
}
