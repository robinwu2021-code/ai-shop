package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO;
import ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Trusted FOOD boundary carries an authenticated actor and preserves the original verification key. */
public interface DiningPickupVerificationInternalApi {
    String VERIFY_PATH="/internal/food/pickup-verification/verify";
    String GET_PATH="/internal/food/pickup-verification/get";
    @PostExchange(VERIFY_PATH) PickupVerificationVO verify(@RequestBody Command command);
    @PostExchange(GET_PATH) PickupVerificationVO get(@RequestBody Query command);
    record Command(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String batchNo,@NotBlank String idempotencyKey,@Valid @NotNull VerifyPickupReq definition) {
        @Override public String toString() { return "FoodPickupVerifyCommand[REDACTED]"; }
    }
    record Query(@NotBlank String entityNo,@NotBlank String storeNo,@NotBlank String actorNo,
            @NotBlank String batchNo,@NotBlank String idempotencyKey) { }
}
