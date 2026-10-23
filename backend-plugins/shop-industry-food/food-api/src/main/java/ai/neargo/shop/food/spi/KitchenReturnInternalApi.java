package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq;
import ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO;
import ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Trusted service-token transport for local kitchen return handling, without financial execution authority. */
public interface KitchenReturnInternalApi {
    String GET_PATH="/internal/food/returns/kitchen/get";
    String HANDLE_PATH="/internal/food/returns/kitchen/handle";
    @PostExchange(GET_PATH) ReturnKitchenVO get(@RequestBody Query command);
    @PostExchange(HANDLE_PATH) HandledVO handle(@RequestBody Command command);
    record Query(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String returnNo) { }
    record Command(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String returnNo,@NotBlank String idempotencyKey,@NotNull @Valid HandleReq definition) { }
}
